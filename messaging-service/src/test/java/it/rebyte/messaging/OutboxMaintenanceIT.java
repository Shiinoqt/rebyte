package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.micrometer.core.instrument.MeterRegistry;
import it.rebyte.messaging.outbox.OutboxClaimRepository;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.outbox.OutboxLagHealthIndicator;
import it.rebyte.messaging.outbox.OutboxPurgeJob;
import it.rebyte.messaging.outbox.OutboxReaper;
import it.rebyte.messaging.support.MessagingInfraTestBase;

/**
 * Lease, reaper, purge, gauge and health behaviour of the outbox. Every
 * scheduled component is pushed far out so no background tick races the
 * assertions.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class OutboxMaintenanceIT extends MessagingInfraTestBase {

    @Autowired MongoTemplate mongo;
    @Autowired OutboxClaimRepository claims;
    @Autowired OutboxReaper reaper;
    @Autowired OutboxPurgeJob purgeJob;
    @Autowired OutboxLagHealthIndicator health;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), OutboxEvent.class);
    }

    @Test
    void reaperReleasesOnlyClaimsOlderThanTheLeaseAndTheRowBecomesClaimableAgain() {
        String stale = insert(Instant.now().minusSeconds(300), null, Instant.now().minus(Duration.ofMinutes(5)));
        String fresh = insert(Instant.now().minusSeconds(200), null, Instant.now());

        assertThat(claims.claimNext()).get().extracting(OutboxEvent::getEventId).isEqualTo(stale);

        mongo.updateFirst(byEventId(stale), Update.update("claimedAt", Instant.now().minus(Duration.ofMinutes(5))), OutboxEvent.class);
        reaper.reap();

        assertThat(find(stale).getClaimedAt()).isNull();
        assertThat(find(fresh).getClaimedAt()).isNotNull();
        assertThat(claims.claimNext()).get().extracting(OutboxEvent::getEventId).isEqualTo(stale);
        assertThat(claims.claimNext()).isEmpty();
    }

    @Test
    void purgeDeletesOnlyPublishedRowsOlderThanRetention() {
        String oldPublished = insert(Instant.now().minus(Duration.ofDays(20)), Instant.now().minus(Duration.ofDays(10)), null);
        String recentPublished = insert(Instant.now().minus(Duration.ofDays(2)), Instant.now().minus(Duration.ofDays(1)), null);
        String unpublished = insert(Instant.now().minus(Duration.ofDays(30)), null, null);

        purgeJob.purge();

        assertThat(mongo.exists(byEventId(oldPublished), OutboxEvent.class)).isFalse();
        assertThat(mongo.exists(byEventId(recentPublished), OutboxEvent.class)).isTrue();
        assertThat(mongo.exists(byEventId(unpublished), OutboxEvent.class)).isTrue();
    }

    @Test
    void gaugesReportPendingClaimedAndLag() {
        insert(Instant.now().minusSeconds(120), null, Instant.now());
        insert(Instant.now().minusSeconds(30), null, null);
        insert(Instant.now().minusSeconds(500), Instant.now(), null);

        assertThat(meters.get("rebyte.outbox.pending").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("rebyte.outbox.claimed").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("rebyte.outbox.lag.seconds").gauge().value()).isBetween(115.0, 180.0);
    }

    @Test
    void healthIsDownPastTheLagThresholdAndUpOnceTheBacklogDrains() {
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        insert(Instant.now().minusSeconds(600), null, null);
        Health degraded = health.health();
        assertThat(degraded.getStatus()).isEqualTo(Status.DOWN);
        assertThat(degraded.getDetails()).containsKeys("lagSeconds", "thresholdSeconds");

        mongo.remove(new Query(), OutboxEvent.class);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    private String insert(Instant occurredAt, Instant publishedAt, Instant claimedAt) {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent(eventId, "email.send", "{}", occurredAt);
        if (publishedAt != null) {
            event.markPublished(publishedAt);
        }
        mongo.insert(event);
        if (claimedAt != null) {
            mongo.updateFirst(byEventId(eventId), Update.update("claimedAt", claimedAt), OutboxEvent.class);
        }
        return eventId;
    }

    private OutboxEvent find(String eventId) {
        return mongo.findOne(byEventId(eventId), OutboxEvent.class);
    }

    private static Query byEventId(String eventId) {
        return Query.query(Criteria.where("eventId").is(eventId));
    }
}
