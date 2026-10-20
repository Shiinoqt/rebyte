package it.rebyte.user.outbox;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The <b>only</b> way a service method emits a domain event (D-14, D-18,
 * T-04-01). Inserts one {@link OutboxEvent} row inside the <em>caller's</em>
 * existing transaction — this class carries no {@code @Transactional} of its
 * own, so it always joins whatever transaction the calling service method is
 * already running in. If that transaction rolls back, the outbox row rolls
 * back with it; if it commits, both the domain change and its event commit
 * atomically.
 *
 * <p><b>No service may call the broker directly.</b> A broker call made
 * outside the database transaction is the dual write that silently loses
 * events on a broker hiccup (D-14, D-18) — every event leaves only through
 * this recorder and {@link OutboxRelay}.
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
     * {@code payload} must carry only identifiers and an instant (D-21) —
     * consumers needing display data call {@code GET /internal/users/{sub}}
     * instead.
     */
    public void record(String eventId, String routingKey, Object payload, Instant occurredAt) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // A payload that cannot serialize is a programming error, not a
            // recoverable runtime condition — fail the caller's transaction
            // rather than silently dropping the event.
            throw new IllegalStateException("failed to serialize outbox payload for event " + eventId, e);
        }
        outboxRepository.save(new OutboxEvent(eventId, routingKey, json, occurredAt));
    }
}
