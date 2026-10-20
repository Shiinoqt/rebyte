package it.rebyte.user.outbox;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per domain event, written by {@link DomainEventRecorder} inside
 * the caller's own transaction and published by {@link OutboxRelay} (D-14,
 * D-18, D-21). Infrastructure entity, not a class from the binding model
 * (docs/rebyte_progettazione.md ch. 10-12) — its {@code id} is a bare
 * surrogate, unlike {@code User}/{@code Admin}, which are keyed by the
 * Cognito {@code sub}.
 *
 * <p>{@code payload} is mapped {@code @JdbcTypeCode(SqlTypes.JSON)} so
 * Hibernate treats the column as native MySQL {@code JSON} both when
 * writing and when validating schema at startup (D-63's
 * {@code ddl-auto: validate}) — a plain {@code String} field without this
 * annotation would infer {@code VARCHAR}/{@code LONGTEXT} and fail
 * validation against the real {@code JSON} column, the same class of
 * mismatch plan 01-03 hit with {@code SellerReview.rating}.
 *
 * <p>{@code published_at} stays {@code null} until {@link OutboxRelay}
 * confirms publication (D-18); the row is never deleted at that point
 * (D-19) — only {@link OutboxPurgeJob} removes it, once past the retention
 * window.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_id", length = 64, nullable = false, updatable = false)
    private String eventId;

    @Column(name = "routing_key", length = 64, nullable = false, updatable = false)
    private String routingKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // richiesto da JPA
    }

    public OutboxEvent(String eventId, String routingKey, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.routingKey = routingKey;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
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

    /** Marks this row published (D-18) — never deletes it (D-19). */
    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
