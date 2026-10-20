package it.rebyte.messaging.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One row per event, written by {@link DomainEventRecorder} inside the
 * caller's transaction and published by {@link OutboxRelay}.
 *
 * <p>{@code eventId} is unique, so a producer that derives it deterministically
 * cannot enqueue the same event twice. {@code claimedAt} exists because a Mongo
 * claim is a committed write, not a session lock: {@link OutboxReaper} clears
 * claims left behind by a relay that died mid-batch. {@code publishedAt} stays
 * null until the relay gets a clean broker confirm; only {@link OutboxPurgeJob}
 * removes published rows.
 */
@Document(collection = "outbox_event")
@CompoundIndex(name = "published_occurred", def = "{'publishedAt': 1, 'occurredAt': 1}")
public class OutboxEvent {

    @Id
    private String id;

    @Indexed(unique = true)
    private final String eventId;
    private final String routingKey;
    private final String payload;
    private final Instant occurredAt;

    private Instant publishedAt;
    private Instant claimedAt;

    public OutboxEvent(String eventId, String routingKey, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.routingKey = routingKey;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public String getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
