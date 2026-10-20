package it.rebyte.payment.outbox;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.payment.domain.Payment;

@Component
public class DomainEventRecorder {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;

    public DomainEventRecorder(OutboxRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void record(String eventType, Payment payment, String eventId) {
        try {
            // payment.failed (a definitive signed denial) and payment.expired (03-10-PLAN.md
            // Task 1 reconciliation) both always represent a no-charge outcome; payment.succeeded
            // and payment.refunded never do.
            boolean terminalNoCharge = "payment.failed".equals(eventType) || "payment.expired".equals(eventType);
            String payload = mapper.writeValueAsString(new PaymentEvent(eventId, payment.getOrderId(),
                    payment.getProviderOrderId(), payment.getProviderCaptureId(), payment.getCaptureOperationId(),
                    terminalNoCharge, java.time.Instant.now()));
            repository.save(new OutboxEvent(eventType, payment.getOrderId(), payload));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Unable to serialize payment event", invalid);
        }
    }

    private record PaymentEvent(String eventId, String orderId, String providerOrderId, String providerCaptureId,
            String captureOperationId, boolean terminalNoCharge, java.time.Instant occurredAt) {
    }
}
