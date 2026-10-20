package it.rebyte.catalog.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Una riga per evento di dominio, scritta da {@link DomainEventRecorder}
 * dentro la transazione del chiamante e pubblicata da {@link OutboxRelay} —
 * l'equivalente Mongo di {@code OutboxEvent} in user-service, con UN campo
 * senza controparte li: {@code claimedAt}.
 *
 * <p>{@code claimedAt} esiste perche la claim Mongo ({@link
 * OutboxClaimRepository}, {@code findAndModify}) e una scrittura committata,
 * non un lock di sessione come {@code SELECT ... FOR UPDATE SKIP LOCKED} di
 * MySQL — un relay che crasha tra la claim e la marcatura pubblicata lascia
 * questa riga claimata per sempre senza un reaper che la liberi dopo la
 * finestra di lease (02-RESEARCH.md Pitfall 3, {@link OutboxReaper}).
 *
 * <p>{@code publishedAt} resta {@code null} finche {@link OutboxRelay} non
 * conferma la pubblicazione; la riga non viene mai cancellata a quel punto —
 * solo {@link OutboxPurgeJob} la rimuove, oltre la finestra di retention.
 */
@Document(collection = "outbox_event")
public class OutboxEvent {

    @Id
    private String id;

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

    /** Marca questa riga pubblicata — non la cancella mai (D-19, come user-service). */
    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }
}
