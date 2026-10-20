package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.outbox.OutboxClaimRepository;
import it.rebyte.catalog.outbox.OutboxEvent;
import it.rebyte.catalog.outbox.OutboxReaper;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Proves the Mongo outbox's one genuinely new failure mode and its
 * mitigation (02-RESEARCH.md Pitfall 3): a {@code findAndModify} claim is a
 * committed write, not a session-held lock, so a relay that crashes
 * mid-batch needs a lease + reaper to recover — MySQL's claim needs
 * neither.
 *
 * <p>{@code fixed-delay-ms}/{@code cron} for every scheduled component are
 * pushed far out via {@code @TestPropertySource} (mirroring user-service's
 * {@code OutboxRelayResilienceIT}) so the background scheduler cannot fire
 * mid-assertion; every drain and every reap in this class is driven
 * explicitly. {@code lease-seconds} is pulled down to 2 so the "after the
 * lease elapses" case does not have to sleep out the 60-second default.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *",
        "rebyte.outbox.relay.lease-seconds=2"
})
class OutboxLeaseClaimIT extends CatalogInfraTestBase {

    private static final String TEST_ROUTING_KEY = "test.lease-claim.event";
    private static final int ROW_COUNT = 100;
    private static final int BATCH_SIZE = 5;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxClaimRepository claimRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OutboxReaper outboxReaper;

    @Autowired
    private OutboxRelay outboxRelay;

    /** Every test writes and drains its own rows against one shared context/database. */
    @AfterEach
    void cleanUpOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    void twoConcurrentRelaysPublishEachEventExactlyOnceWithNoDuplicateClaims() throws Exception {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
        admin.declareExchange(exchange);
        Queue testQueue = new Queue("test.lease-claim." + UUID.randomUUID(), true, false, false);
        admin.declareQueue(testQueue);
        admin.declareBinding(BindingBuilder.bind(testQueue).to(exchange).with(TEST_ROUTING_KEY));

        Set<String> writtenEventIds = writeRows(ROW_COUNT);

        OutboxRelay relayA = new OutboxRelay(
                claimRepository, outboxRepository, rabbitTemplate, transactionManager, BATCH_SIZE, 200, 5000);
        OutboxRelay relayB = new OutboxRelay(
                claimRepository, outboxRepository, rabbitTemplate, transactionManager, BATCH_SIZE, 200, 5000);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> futureA = CompletableFuture.runAsync(relayA::drainUntilEmptyOrCapped, executor);
            CompletableFuture<Void> futureB = CompletableFuture.runAsync(relayB::drainUntilEmptyOrCapped, executor);
            CompletableFuture.allOf(futureA, futureB).get(60, TimeUnit.SECONDS);

            // A drain can legitimately end early if its own claim came back
            // partial while the other relay still held rows — keep draining
            // sequentially until the table is genuinely empty; the behaviour
            // under test (exactly one claimant per row) already happened above.
            while (outboxRepository.countByPublishedAtIsNull() > 0) {
                relayA.drainUntilEmptyOrCapped();
            }
        } finally {
            executor.shutdown();
        }

        assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
        assertThat(outboxRepository.findAll()).allMatch(row -> row.getPublishedAt() != null);

        List<String> receivedEventIds = drainQueue(testQueue.getName());
        assertThat(receivedEventIds).hasSize(ROW_COUNT);
        assertThat(receivedEventIds).doesNotHaveDuplicates();
        assertThat(new HashSet<>(receivedEventIds)).isEqualTo(writtenEventIds);

        admin.deleteQueue(testQueue.getName());
    }

    @Test
    void abandonedClaimIsNotReclaimedBeforeTheLeaseElapsesAndIsPublishedAfterTheReaperRuns() {
        String eventId = "lease-claim-crash-" + UUID.randomUUID();
        outboxRepository.save(new OutboxEvent(eventId, TEST_ROUTING_KEY, payloadFor(eventId), Instant.now()));

        // Simulate a crash: claim directly through the claim repository,
        // exactly like a relay's transaction would, then never mark it
        // published — nothing else in this test process ever touches it
        // again, mirroring a relay instance that died mid-batch.
        OutboxEvent claimed = claimRepository.claimNext().orElseThrow();
        assertThat(claimed.getEventId()).isEqualTo(eventId);

        // Before the lease elapses, the claim is not reclaimable — the only
        // row in the table is now claimed, so a fresh claim attempt finds
        // nothing.
        assertThat(claimRepository.claimNext()).isEmpty();
        assertThat(outboxRepository.countByClaimedAtIsNotNullAndPublishedAtIsNull()).isEqualTo(1);

        awaitLeaseWindowElapsed();

        outboxReaper.reap();
        assertThat(outboxRepository.countByClaimedAtIsNotNullAndPublishedAtIsNull()).isZero();

        // A subsequent relay tick now claims and publishes it normally.
        outboxRelay.drainUntilEmptyOrCapped();

        OutboxEvent published = outboxRepository.findAll().stream()
                .filter(row -> row.getEventId().equals(eventId))
                .findFirst()
                .orElseThrow();
        assertThat(published.getPublishedAt()).isNotNull();
    }

    /** {@code rebyte.outbox.relay.lease-seconds=2} (test property) — comfortably longer, never a fixed sleep out of the 60s default. */
    private void awaitLeaseWindowElapsed() {
        try {
            Thread.sleep(2500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Set<String> writeRows(int count) {
        Set<String> eventIds = new HashSet<>();
        for (int i = 0; i < count; i++) {
            String eventId = "lease-claim-" + i + "-" + UUID.randomUUID();
            eventIds.add(eventId);
            outboxRepository.save(new OutboxEvent(eventId, TEST_ROUTING_KEY, payloadFor(eventId), Instant.now()));
        }
        return eventIds;
    }

    private String payloadFor(String eventId) {
        return "{\"eventId\":\"" + eventId + "\"}";
    }

    private List<String> drainQueue(String queueName) throws Exception {
        List<String> eventIds = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(queueName, 1000)) != null) {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(body);
            eventIds.add(node.get("eventId").asText());
        }
        return eventIds;
    }
}
