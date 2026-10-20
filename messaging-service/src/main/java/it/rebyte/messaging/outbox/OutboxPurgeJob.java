package it.rebyte.messaging.outbox;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes published rows past the retention window. Published rows are kept
 * for that long so a duplicate-publish or missing-event investigation has a
 * record to read.
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
            long deleted = outboxRepository.deleteByPublishedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("purged {} published outbox rows older than {}", deleted, cutoff);
            }
        } catch (Exception e) {
            log.error("outbox purge job failed", e);
        }
    }
}
