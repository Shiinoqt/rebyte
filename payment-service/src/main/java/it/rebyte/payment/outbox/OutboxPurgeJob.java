package it.rebyte.payment.outbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPurgeJob {

    private final OutboxRepository repository;

    public OutboxPurgeJob(OutboxRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "${rebyte.outbox.purge.cron:0 0 3 * * *}")
    @Transactional
    public void purge() {
        repository.deletePublishedBefore(Instant.now().minus(30, ChronoUnit.DAYS));
    }
}
