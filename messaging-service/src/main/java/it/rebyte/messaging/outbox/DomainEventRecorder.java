package it.rebyte.messaging.outbox;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The only way messaging-service emits a message to the broker. Inserts one
 * {@link OutboxEvent} inside the caller's existing transaction: this class has
 * no {@code @Transactional} of its own, so the row commits or rolls back with
 * the caller's domain change. Nothing outside this package publishes to
 * RabbitMQ.
 */
@Component
public class DomainEventRecorder {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public DomainEventRecorder(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /** Serializes {@code payload} to JSON and inserts the outbox row. */
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
