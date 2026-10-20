package it.rebyte.order.outbox;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final long confirmTimeoutMillis;

    public OutboxRelay(OutboxRepository repository, RabbitTemplate rabbit, PlatformTransactionManager manager,
            @Value("${rebyte.outbox.relay.batch-size:100}") int batchSize,
            @Value("${rebyte.outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMillis) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.transaction = new TransactionTemplate(manager);
        // Under REPEATABLE READ, concurrent SKIP LOCKED claims hold gap locks on the unpublished index
        // and deadlock when each relay moves its rows out of the NULL range.
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.batchSize = batchSize;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    @Scheduled(fixedDelayString = "${rebyte.outbox.relay.fixed-delay-ms:1000}",
            initialDelayString = "${rebyte.outbox.relay.initial-delay-ms:1000}")
    public void drain() {
        try {
            for (int batch = 0; batch < 50 && publishNextBatch(); batch++) {
                // A bounded tick drains a burst without monopolizing the scheduler.
            }
        } catch (Exception failure) {
            log.error("Order outbox relay tick failed", failure);
        }
    }

    public boolean publishNextBatch() {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            List<OutboxEvent> rows = repository.claimBatch(PageRequest.of(0, batchSize));
            if (rows.isEmpty()) {
                return false;
            }
            Boolean confirmed = rabbit.invoke(template -> {
                for (OutboxEvent row : rows) {
                    template.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, row.getEventType(), row.getPayload());
                }
                return template.waitForConfirms(confirmTimeoutMillis);
            });
            if (!Boolean.TRUE.equals(confirmed)) {
                status.setRollbackOnly();
                return false;
            }
            Instant now = Instant.now();
            rows.forEach(row -> row.markPublished(now));
            return rows.size() == batchSize;
        }));
    }
}
