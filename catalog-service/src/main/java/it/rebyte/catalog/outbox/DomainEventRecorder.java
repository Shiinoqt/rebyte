package it.rebyte.catalog.outbox;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The <b>only</b> way a catalog-service method emits a domain event —
 * copied verbatim in contract from user-service's recorder. Inserts one
 * {@link OutboxEvent} inside the <em>caller's</em> existing transaction;
 * this class carries no {@code @Transactional} of its own, so it always
 * joins whatever transaction the calling service method is already running
 * in. If that transaction rolls back, the outbox row rolls back with it; if
 * it commits, both the domain change and its event commit atomically.
 *
 * <p><b>No service may call the broker directly.</b> Every event leaves
 * only through this recorder and {@link OutboxRelay} — no class outside
 * {@code it.rebyte.catalog.outbox} names the shared exchange literal or
 * publishes to RabbitMQ.
 */
@Component
public class DomainEventRecorder {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public DomainEventRecorder(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Serializes {@code payload} to JSON and inserts the outbox row.
     * {@code payload} must carry only identifiers and an instant — the same
     * rule user-service's recorder enforces.
     */
    public void record(String eventId, String routingKey, Object payload, Instant occurredAt) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload for event " + eventId, e);
        }
        outboxRepository.save(new OutboxEvent(eventId, routingKey, json, occurredAt));
    }
}
