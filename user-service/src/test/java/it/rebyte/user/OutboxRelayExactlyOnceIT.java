package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.user.messaging.UserFollowedEvent;
import it.rebyte.user.outbox.OutboxEvent;
import it.rebyte.user.outbox.OutboxRelay;
import it.rebyte.user.outbox.OutboxRepository;
import it.rebyte.user.support.RabbitTestBase;

/**
 * Success criterion 5 / D-23: with N unpublished outbox rows and two relay
 * instances draining concurrently, the queue receives exactly N messages —
 * no duplicates, none missing — every row ends with a non-null
 * {@code published_at}, and re-draining an empty table publishes nothing
 * further.
 *
 * <p><b>How concurrency is produced (research assumption A6).</b> Not two
 * full Spring contexts — two plain {@link OutboxRelay} instances,
 * hand-constructed (bypassing the Spring-managed bean) but sharing the
 * same real, Spring-wired {@link OutboxRepository}, {@link RabbitTemplate}
 * and {@link PlatformTransactionManager}, driven from two separate threads
 * via {@link CompletableFuture#runAsync}. The concurrency this test
 * exercises is at the database connection-pool / row-lock level (D-14's
 * skip-locked claim), not at the Spring context level — two independent
 * transactional invocations racing the same claim query against the same
 * MySQL container is what {@code SELECT ... FOR UPDATE SKIP LOCKED} exists
 * to arbitrate. {@code N=300} with a small {@code batchSize=25} forces at
 * least twelve claim rounds, so a serialized relay would visibly take a
 * different (much slower, strictly sequential) path than a genuinely
 * interleaved one. The assertion itself is the real backstop regardless of
 * how faithfully the interleave was produced: if a row is ever claimed
 * twice, the exact-count and no-duplicate-id assertions fail.
 *
 * <p>No mock anywhere in this test — real MySQL and RabbitMQ containers
 * (via {@link RabbitTestBase}), real skip-locked claim, real publisher
 * confirms, a real bound queue actually consumed.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class OutboxRelayExactlyOnceIT extends RabbitTestBase {

    private static final int ROW_COUNT = 300;
    private static final int BATCH_SIZE = 25;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void exactlyNMessagesPublishedUnderTwoConcurrentRelaysNoDuplicatesNoneMissing() throws Exception {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
        admin.declareExchange(exchange);
        // A plain durable, non-exclusive, non-auto-delete queue — not
        // AnonymousQueue: an exclusive/auto-delete queue is scoped to its
        // declaring connection and was observed disappearing mid-test
        // (broker returned 404 NOT_FOUND on receive) under this test's
        // sustained concurrent publish/confirm load from two relay
        // threads. A named queue this test owns end to end is what a real
        // consumer service (catalog/messaging, later phases) would
        // declare anyway.
        Queue testQueue = new Queue("test.exactly-once." + java.util.UUID.randomUUID(), true, false, false);
        admin.declareQueue(testQueue);
        admin.declareBinding(BindingBuilder.bind(testQueue).to(exchange).with("user.*"));

        Set<String> writtenEventIds = writeRows(ROW_COUNT);

        OutboxRelay relayA = new OutboxRelay(outboxRepository, rabbitTemplate, transactionManager, BATCH_SIZE, 100, 5000);
        OutboxRelay relayB = new OutboxRelay(outboxRepository, rabbitTemplate, transactionManager, BATCH_SIZE, 100, 5000);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> futureA = CompletableFuture.runAsync(relayA::drainUntilEmptyOrCapped, executor);
            CompletableFuture<Void> futureB = CompletableFuture.runAsync(relayB::drainUntilEmptyOrCapped, executor);
            CompletableFuture.allOf(futureA, futureB).get(60, TimeUnit.SECONDS);

            // A drain can legitimately end early on CONFIRMED_DRAINED once its
            // own claim came back partial while the other relay still holds
            // rows mid-transaction; keep draining sequentially here until the
            // table is genuinely empty (the behaviour under test — exactly
            // one claimant per row — already happened above).
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

        assertThat(meterRegistry.get("rebyte.outbox.pending").gauge().value()).isZero();
        assertThat(meterRegistry.get("rebyte.outbox.lag.seconds").gauge().value()).isZero();

        // Re-draining an already-empty table publishes nothing further.
        relayA.drainUntilEmptyOrCapped();
        assertThat(drainQueue(testQueue.getName())).isEmpty();

        outboxRepository.deleteAll();
        admin.deleteQueue(testQueue.getName());
    }

    private Set<String> writeRows(int count) throws Exception {
        Set<String> eventIds = new HashSet<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                String eventId = "exactly-once-" + i;
                eventIds.add(eventId);
                Instant occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
                UserFollowedEvent event = new UserFollowedEvent(
                        eventId, "follower-" + i, "followed-" + i, occurredAt);
                String json;
                try {
                    json = objectMapper.writeValueAsString(event);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                outboxRepository.save(new OutboxEvent(eventId, event.type(), json, occurredAt));
            }
        });
        return eventIds;
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
