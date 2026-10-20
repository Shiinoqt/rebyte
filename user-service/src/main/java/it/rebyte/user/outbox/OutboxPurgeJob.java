package it.rebyte.user.outbox;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes published rows past the retention window (D-19), defaulted to
 * seven days. Published rows are retained (never deleted at publish time)
 * specifically so a duplicate-publish or missing-event investigation has a
 * record to read; this job is what keeps that record bounded rather than
 * growing forever. Runs daily by default on {@link it.rebyte.user.config.SchedulingConfig}'s
 * dedicated scheduler, so a long purge run can never delay
 * {@link OutboxRelay}'s one-second tick.
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
    @Transactional
    public void purge() {
        try {
            Instant cutoff = Instant.now().minus(retention);
            long deleted = outboxRepository.deletePublishedBefore(cutoff);
            if (deleted > 0) {
                log.info("purged {} published outbox rows older than {}", deleted, cutoff);
            }
        } catch (Exception e) {
            // Same discipline as OutboxRelay#drain (D-16): never let an
            // exception escape a scheduled method.
            log.error("outbox purge job failed", e);
        }
    }
}
