package it.rebyte.payment.provider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.payment.error.PayPalProviderException;
import it.rebyte.payment.provider.PayPalDtos.CaptureResponse;
import it.rebyte.payment.provider.PayPalDtos.ErrorDetail;
import it.rebyte.payment.provider.PayPalDtos.OrderRequest;
import it.rebyte.payment.provider.PayPalDtos.OrderResponse;
import it.rebyte.payment.provider.PayPalDtos.ProviderError;
import it.rebyte.payment.provider.PayPalDtos.RefundResponse;
import it.rebyte.payment.provider.PayPalDtos.TokenResponse;

/**
 * The one concrete PayPal provider client (D-21, D-23): hand-written Spring
 * {@link RestClient}, OAuth2 client_credentials, Orders v2 create/get/
 * capture, Payments v2 full refund. No {@code PaymentGateway} port — a
 * second provider would extract one, per CLAUDE.md's rule against an
 * interface with only one real implementation.
 *
 * <p>Token caching: a client_credentials access token is fetched once and
 * reused until 60s before its declared {@code expires_in}, guarded by a
 * lock so concurrent callers do not each mint a fresh token.
 *
 * <p>Every method maps a provider 4xx/5xx response or a connect/read
 * timeout to {@link PayPalProviderException} — never a raw {@code
 * RestClientException} escapes this class (03-RESEARCH.md "Concrete PayPal
 * fields"; explicit provider error mapping required by 03-05-PLAN.md Task 1).
 */
@Component
public class PayPalClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;

    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public PayPalClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
            @Value("${rebyte.paypal.base-url}") String baseUrl,
            @Value("${rebyte.paypal.client-id}") String clientId,
            @Value("${rebyte.paypal.client-secret}") String clientSecret,
            @Value("${rebyte.paypal.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${rebyte.paypal.read-timeout-ms}") long readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;

        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public OrderResponse createOrder(OrderRequest request, String requestId) {
        try {
            return restClient.post()
                    .uri("/v2/checkout/orders")
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .header("PayPal-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(OrderResponse.class);
        } catch (ResourceAccessException timeout) {
            throw new PayPalProviderException("PayPal order create timed out or was unreachable", timeout);
        }
    }

    public OrderResponse getOrder(String providerOrderId) {
        try {
            return restClient.get()
                    .uri("/v2/checkout/orders/{id}", providerOrderId)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(OrderResponse.class);
        } catch (ResourceAccessException timeout) {
            throw new PayPalProviderException("PayPal order get timed out or was unreachable", timeout);
        }
    }

    public CaptureResponse captureOrder(String providerOrderId, String requestId) {
        try {
            return restClient.post()
                    .uri("/v2/checkout/orders/{id}/capture", providerOrderId)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .header("PayPal-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(CaptureResponse.class);
        } catch (ResourceAccessException timeout) {
            throw new PayPalProviderException("PayPal capture timed out or was unreachable", timeout);
        }
    }

    public RefundResponse refundCapture(String providerCaptureId, String requestId) {
        try {
            return restClient.post()
                    .uri("/v2/payments/captures/{id}/refund", providerCaptureId)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .header("PayPal-Request-Id", requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(RefundResponse.class);
        } catch (ResourceAccessException timeout) {
            throw new PayPalProviderException("PayPal refund timed out or was unreachable", timeout);
        }
    }

    private String bearer() {
        return "Bearer " + accessToken();
    }

    private String accessToken() {
        Instant now = Instant.now();
        String token = cachedToken;
        if (token != null && now.isBefore(tokenExpiry)) {
            return token;
        }
        tokenLock.lock();
        try {
            now = Instant.now();
            if (cachedToken != null && now.isBefore(tokenExpiry)) {
                return cachedToken;
            }
            TokenResponse response = fetchToken();
            cachedToken = response.accessToken();
            tokenExpiry = now.plusSeconds(Math.max(0, response.expiresInSeconds() - 60));
            return cachedToken;
        } finally {
            tokenLock.unlock();
        }
    }

    private TokenResponse fetchToken() {
        String credentials = clientId + ":" + clientSecret;
        String basic = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        try {
            return restClient.post()
                    .uri("/v1/oauth2/token")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=client_credentials")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .body(TokenResponse.class);
        } catch (ResourceAccessException timeout) {
            throw new PayPalProviderException("PayPal OAuth token request timed out or was unreachable", timeout);
        }
    }

    private void handleError(HttpRequest request, ClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        String providerErrorName = null;
        List<String> issues = List.of();
        try (InputStream body = response.getBody()) {
            ProviderError error = objectMapper.readValue(body, ProviderError.class);
            providerErrorName = error.name();
            if (error.details() != null) {
                issues = error.details().stream().map(ErrorDetail::issue).filter(Objects::nonNull).toList();
            }
        } catch (IOException ignored) {
            // Body absent/unparseable — still surface the HTTP status below.
        }
        throw new PayPalProviderException(status, providerErrorName, issues);
    }
}
