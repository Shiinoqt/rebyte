package it.rebyte.payment.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.outbox.DomainEventRecorder;
import it.rebyte.payment.repository.PaymentRepository;

@Service
public class PaymentWebhookService {

    private static final String CAPTURE_PATH = "/v2/payments/captures/";

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final DomainEventRecorder recorder;

    public PaymentWebhookService(ObjectMapper mapper, JdbcTemplate jdbc, PaymentRepository payments,
            DomainEventRecorder recorder) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.payments = payments;
        this.recorder = recorder;
    }

    @Transactional
    public void acceptVerified(byte[] rawBody) {
        JsonNode event;
        try {
            event = mapper.readTree(rawBody);
        } catch (IOException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook JSON");
        }
        String eventId = required(event.path("id"), 100);
        String type = required(event.path("event_type"), 100);
        int inserted = jdbc.update("INSERT IGNORE INTO paypal_webhook_events (event_id, event_type, received_at) VALUES (?, ?, ?)",
                eventId, type, java.sql.Timestamp.from(Instant.now()));
        if (inserted == 0) {
            return;
        }
        String status = switch (type) {
            case "PAYMENT.CAPTURE.COMPLETED" -> "COMPLETED";
            case "PAYMENT.CAPTURE.DENIED" -> "DENIED";
            case "PAYMENT.CAPTURE.PENDING" -> "PENDING";
            case "PAYMENT.CAPTURE.REFUNDED" -> "REFUNDED";
            default -> null;
        };
        if (status == null) {
            return;
        }

        JsonNode resource = event.path("resource");
        String captureId = "REFUNDED".equals(status)
                ? refundedCaptureId(resource)
                : required(resource.path("id"), 64);
        Payment payment;
        if ("REFUNDED".equals(status)) {
            payment = payments.findByProviderCaptureId(captureId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Unknown capture"));
        } else {
            String providerOrderId = required(resource.path("supplementary_data").path("related_ids")
                    .path("order_id"), 64);
            payment = payments.findByProviderOrderId(providerOrderId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                            "Unknown PayPal order"));
        }
        String customId = resource.path("custom_id").asText(null);
        if (customId != null && !payment.getOrderId().equals(customId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Order correlation mismatch");
        }
        JsonNode amount = resource.path("amount");
        BigDecimal providerAmount;
        try {
            providerAmount = new BigDecimal(required(amount.path("value"), 20));
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid capture amount");
        }
        if (!"EUR".equals(amount.path("currency_code").asText())
                || providerAmount.compareTo(payment.getPricePaid()) != 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Capture amount mismatch");
        }
        boolean changed;
        try {
            changed = payment.applyCaptureEvent(captureId, status);
        } catch (IllegalArgumentException mismatch) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Capture correlation mismatch");
        }
        if (changed && !"PENDING".equals(status)) {
            String domainType = switch (status) {
                case "COMPLETED" -> "payment.succeeded";
                case "DENIED" -> "payment.failed";
                default -> "payment.refunded";
            };
            recorder.record(domainType, payment, eventId);
            // 03-10-PLAN.md Task 1 (D-26/D-29): a genuine verified webhook resolves
            // the outcome either way — clear the sweep's "awaiting webhook" flag and
            // exclude this row from further reconciliation.
            payment.markWebhookConfirmed(Instant.now());
        }
    }

    /**
     * A PayPal refund resource carries no supplementary_data; its only link to
     * the refunded capture is the {@code rel=up} HATEOAS link.
     */
    private String refundedCaptureId(JsonNode resource) {
        for (JsonNode link : resource.path("links")) {
            if (!"up".equals(link.path("rel").asText())) {
                continue;
            }
            String path;
            try {
                path = URI.create(link.path("href").asText("")).getPath();
            } catch (IllegalArgumentException invalid) {
                break;
            }
            if (path != null && path.startsWith(CAPTURE_PATH)) {
                String id = path.substring(CAPTURE_PATH.length());
                if (!id.isBlank() && id.length() <= 64 && id.indexOf('/') < 0) {
                    return id;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook field");
    }

    private String required(JsonNode value, int maxLength) {
        String text = value.isTextual() ? value.asText() : null;
        if (text == null || text.isBlank() || text.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook field");
        }
        return text;
    }
}
