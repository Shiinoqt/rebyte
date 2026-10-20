package it.rebyte.payment.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Service
public class PayPalWebhookVerifier {

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private final String webhookId;
    private final boolean testProfile;
    private final Set<String> paypalHosts;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<URI, CachedCertificate> certificates = new ConcurrentHashMap<>();

    public PayPalWebhookVerifier(@Value("${rebyte.paypal.webhook-id:}") String webhookId,
            @Value("${rebyte.paypal.base-url:https://api-m.sandbox.paypal.com}") String baseUrl,
            Environment environment) {
        this.webhookId = webhookId;
        this.testProfile = Set.of(environment.getActiveProfiles()).contains("test");
        this.paypalHosts = baseUrl.contains(".sandbox.paypal.com")
                ? Set.of("api.sandbox.paypal.com", "api-m.sandbox.paypal.com")
                : Set.of("api.paypal.com", "api-m.paypal.com");
    }

    public boolean verify(byte[] body, HttpHeaders headers) {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES || webhookId.isBlank()) {
            return false;
        }
        String transmissionId = one(headers, "paypal-transmission-id");
        String timestamp = one(headers, "paypal-transmission-time");
        String certUrl = one(headers, "paypal-cert-url");
        String signature = one(headers, "paypal-transmission-sig");
        String algorithm = one(headers, "paypal-auth-algo");
        if (transmissionId == null || timestamp == null || certUrl == null || signature == null
                || !"SHA256withRSA".equalsIgnoreCase(algorithm)) {
            return false;
        }
        try {
            Instant.parse(timestamp);
            URI uri = URI.create(certUrl);
            if (!allowed(uri)) {
                return false;
            }
            CRC32 crc = new CRC32();
            crc.update(body);
            String message = transmissionId + "|" + timestamp + "|" + webhookId + "|" + crc.getValue();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(certificate(uri).getPublicKey());
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception invalid) {
            return false;
        }
    }

    private String one(HttpHeaders headers, String name) {
        var values = headers.get(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank() || values.getFirst().length() > 2048) {
            return null;
        }
        return values.getFirst();
    }

    private boolean allowed(URI uri) {
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return false;
        }
        if (uri.getPort() == -1 && "https".equalsIgnoreCase(uri.getScheme()) && paypalHosts.contains(uri.getHost())
                && uri.getPath() != null && uri.getPath().matches("/v1/notifications/certs/[A-Za-z0-9._-]+")) {
            return true;
        }
        return testProfile && "http".equalsIgnoreCase(uri.getScheme())
                && "localhost".equalsIgnoreCase(uri.getHost())
                && uri.getPath() != null && uri.getPath().startsWith("/test-certs/");
    }

    private X509Certificate certificate(URI uri) throws Exception {
        CachedCertificate cached = certificates.get(uri);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            cached.certificate().checkValidity();
            return cached.certificate();
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length > 16 * 1024) {
            throw new IllegalArgumentException("Invalid PayPal certificate response");
        }
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(response.body()));
        certificate.checkValidity();
        if (certificates.size() >= 32) {
            certificates.clear();
        }
        certificates.put(uri, new CachedCertificate(certificate, Instant.now().plus(Duration.ofHours(1))));
        return certificate;
    }

    private record CachedCertificate(X509Certificate certificate, Instant expiresAt) {
    }
}
