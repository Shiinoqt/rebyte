package it.rebyte.catalog.outbox;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes published rows past the retention window, on
 * {@link it.rebyte.catalog.config.SchedulingConfig}'s dedicated scheduler
 * pool so a long purge run can never delay {@link OutboxRelay}'s one-second
 * tick or {@link OutboxReaper}'s sweep — mirrors user-service's
 * {@code OutboxPurgeJob} exactly. Published rows are retained specifically
 * so a duplicate-publish or missing-event investigation has a record to
 * read; this job is what keeps that record bounded rather than growing
 * forever.
 */
@Component
public class OutboxPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);

    private final OutboxRepository outboxRepository;
    private final Duration retention;

    public OutboxPurgeJob(
            OutboxRepository outboxRepository,
            @Value("${rebyte.outbox.purge.retention-days:7}") long retentionDays) {
        this.outboxRepository = outboxRepository;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(cron = "${rebyte.outbox.purge.cron:0 0 3 * * *}")
    public void purge() {
        try {
            Instant cutoff = Instant.now().minus(retention);
            long deleted = outboxRepository.deleteByPublishedAtNotNullAndPublishedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("purged {} published outbox rows older than {}", deleted, cutoff);
            }
        } catch (Exception e) {
            // Same discipline as OutboxRelay#drain and OutboxReaper#reap:
            // never let an exception escape a scheduled method.
            log.error("outbox purge job failed", e);
        }
    }
}
