package it.rebyte.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentMethod;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"local-auth", "test"})
@TestPropertySource(properties = "rebyte.paypal.webhook-id=test-webhook")
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
class SignedWebhookIT extends PaymentIntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void signedCaptureAdvancesOnceWhileDuplicateAndAlteredBodyDoNot() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String providerOrderId = "PAYPAL-" + UUID.randomUUID();
        String captureId = "CAPTURE-" + UUID.randomUUID();
        Payment payment = new Payment(orderId, "buyer", new BigDecimal("12.37"), new BigDecimal("4.19"),
                new BigDecimal("16.56"), "Listing", PaymentMethod.PAYPAL, "create-" + orderId, Instant.now());
        payments.saveAndFlush(payment);
        payments.recordProviderOrder(orderId, providerOrderId, "https://example.invalid/approve");

        String cert = new String(new ClassPathResource("webhook/test-cert.pem").getInputStream().readAllBytes(),
                StandardCharsets.US_ASCII);
        PAYPAL.stubFor(get(urlEqualTo("/test-certs/test-cert.pem"))
                .willReturn(aResponse().withStatus(200).withBody(cert)));

        String eventId = UUID.randomUUID().toString();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\","
                + "\"resource\":{\"id\":\"" + captureId + "\",\"custom_id\":\"" + orderId + "\","
                + "\"amount\":{\"currency_code\":\"EUR\",\"value\":\"16.56\"},"
                + "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + providerOrderId + "\"}}}}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(deliver(body, sign(body)).getStatusCode().value()).isEqualTo(200);
        assertThat(deliver(body, sign(body)).getStatusCode().value()).isEqualTo(200);
        assertThat(count("paypal_webhook_events", eventId)).isEqualTo(1);
        assertThat(count("payment_outbox_events", orderId)).isEqualTo(1);
        assertThat(payments.findById(orderId).orElseThrow().getProviderCaptureStatus()).isEqualTo("COMPLETED");

        byte[] changed = (new String(body, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
        assertThat(deliver(changed, sign(body)).getStatusCode().value()).isEqualTo(400);
        assertThat(count("paypal_webhook_events", eventId)).isEqualTo(1);
        assertThat(count("payment_outbox_events", orderId)).isEqualTo(1);

        String mismatchedEventId = UUID.randomUUID().toString();
        byte[] mismatched = new String(body, StandardCharsets.UTF_8).replace(eventId, mismatchedEventId)
                .replace("\"custom_id\":\"" + orderId + "\"", "\"custom_id\":\"other-order\"")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(deliver(mismatched, sign(mismatched)).getStatusCode().value()).isEqualTo(422);
        assertThat(count("paypal_webhook_events", mismatchedEventId)).isZero();
        assertThat(count("payment_outbox_events", orderId)).isEqualTo(1);
    }

    @Test
    void signedCaptureConfirmsASynchronouslyCompletedCapture() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String providerOrderId = "PAYPAL-" + UUID.randomUUID();
        String captureId = "CAPTURE-" + UUID.randomUUID();
        Payment payment = new Payment(orderId, "buyer", new BigDecimal("12.37"), new BigDecimal("4.19"),
                new BigDecimal("16.56"), "Listing", PaymentMethod.PAYPAL, "create-" + orderId, Instant.now());
        payments.saveAndFlush(payment);
        payments.recordProviderOrder(orderId, providerOrderId, "https://example.invalid/approve");
        payments.claimCapturing(orderId, "op-" + UUID.randomUUID(), "req-" + UUID.randomUUID(),
                PaymentState.OPEN, PaymentState.CAPTURING);
        payments.claimCaptureDispatch(orderId, PaymentState.CAPTURING, Instant.now());
        // PayPal's synchronous capture response is already COMPLETED before the webhook arrives.
        payments.recordCapture(orderId, PaymentState.CAPTURING, captureId, "COMPLETED");

        String cert = new String(new ClassPathResource("webhook/test-cert.pem").getInputStream().readAllBytes(),
                StandardCharsets.US_ASCII);
        PAYPAL.stubFor(get(urlEqualTo("/test-certs/test-cert.pem"))
                .willReturn(aResponse().withStatus(200).withBody(cert)));

        String eventId = UUID.randomUUID().toString();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\","
                + "\"resource\":{\"id\":\"" + captureId + "\",\"custom_id\":\"" + orderId + "\","
                + "\"amount\":{\"currency_code\":\"EUR\",\"value\":\"16.56\"},"
                + "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + providerOrderId + "\"}}}}")
                .getBytes(StandardCharsets.UTF_8);

        assertThat(deliver(body, sign(body)).getStatusCode().value()).isEqualTo(200);
        assertThat(count("payment_outbox_events", orderId)).isEqualTo(1);
        Payment confirmed = payments.findById(orderId).orElseThrow();
        assertThat(confirmed.getWebhookConfirmedAt()).isNotNull();
        assertThat(confirmed.isVerifiedCapture()).isTrue();

        String replayId = UUID.randomUUID().toString();
        byte[] replay = new String(body, StandardCharsets.UTF_8).replace(eventId, replayId)
                .getBytes(StandardCharsets.UTF_8);
        assertThat(deliver(replay, sign(replay)).getStatusCode().value()).isEqualTo(200);
        assertThat(count("payment_outbox_events", orderId)).isEqualTo(1);
    }

    @Test
    void forgedSignatureAndUntrustedCertificateUrlWriteNothing() throws Exception {
        String eventId = UUID.randomUUID().toString();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"event_type\":\"IGNORED\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(deliver(body, "invalid-signature").getStatusCode().value()).isEqualTo(400);
        assertThat(count("paypal_webhook_events", eventId)).isZero();

        HttpHeaders headers = signedHeaders(body, sign(body));
        headers.set("paypal-cert-url", "http://127.0.0.1/internal");
        assertThat(rest.postForEntity("/payments/webhook", new HttpEntity<>(body, headers), Void.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(count("paypal_webhook_events", eventId)).isZero();
    }

    private ResponseEntity<Void> deliver(byte[] body, String signature) {
        return rest.postForEntity("/payments/webhook", new HttpEntity<>(body, signedHeaders(body, signature)),
                Void.class);
    }

    private HttpHeaders signedHeaders(byte[] body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("paypal-transmission-id", "transmission-1");
        headers.set("paypal-transmission-time", "2026-09-24T14:00:00Z");
        headers.set("paypal-auth-algo", "SHA256withRSA");
        headers.set("paypal-cert-url", PAYPAL.getRuntimeInfo().getHttpBaseUrl() + "/test-certs/test-cert.pem");
        headers.set("paypal-transmission-sig", signature);
        return headers;
    }

    private String sign(byte[] body) throws Exception {
        String pem = new String(new ClassPathResource("webhook/test-key.pem").getInputStream().readAllBytes(),
                StandardCharsets.US_ASCII).replaceAll("-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        CRC32 crc = new CRC32();
        crc.update(body);
        String message = "transmission-1|2026-09-24T14:00:00Z|test-webhook|" + crc.getValue();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private long count(String table, String id) {
        String column = "paypal_webhook_events".equals(table) ? "event_id" : "aggregate_id";
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, id);
    }
}
