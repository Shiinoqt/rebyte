package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.support.MessagingInfraTestBase;

/**
 * Proves routing and dead-lettering against a real broker. Listener
 * containers stay stopped so no consumer added by a later plan drains a queue
 * while depths are being asserted, and the relay is pushed far out so it never
 * publishes into the exchange.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class RabbitTopologyIT extends MessagingInfraTestBase {

    private static final String DEVELOPER_ACTIVATED = "user.developer_activated";
    private static final long DEADLINE_MS = 10_000;
    private static final long STABLE_MS = 750;

    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;

    @Test
    void everyRoutingKeyReachesExactlyTheQueuesThatConsumeIt() {
        purgeAll();
        Map<String, Set<String>> expectedKeysByQueue = RabbitTopologyTest.ROUTING_MATRIX;
        List<String> published = allRoutingKeys();
        published.forEach(this::publish);

        int expectedTotal = expectedKeysByQueue.values().stream().mapToInt(Set::size).sum();
        awaitStableTotalDepth(expectedTotal);

        Map<String, Set<String>> actualKeysByQueue = new TreeMap<>();
        for (String queue : expectedKeysByQueue.keySet()) {
            Set<String> keys = new TreeSet<>();
            byte[] body;
            while ((body = receiveBody(queue)) != null) {
                String key = new String(body, StandardCharsets.UTF_8);
                assertThat(keys.add(key)).as("%s delivered %s twice", queue, key).isTrue();
            }
            actualKeysByQueue.put(queue, keys);
        }
        assertThat(actualKeysByQueue).isEqualTo(new TreeMap<>(expectedKeysByQueue));
        RabbitConfig.DEAD_LETTER_ORIGINS.keySet().forEach(dlq -> assertThat(depth(dlq)).as(dlq).isZero());
    }

    @Test
    void excludedKeysReachNoMessagingQueue() {
        Set<String> routed = new TreeSet<>();
        RabbitTopologyTest.ROUTING_MATRIX.values().forEach(routed::addAll);

        Set<String> excluded = Set.of(
                RebyteExchanges.ORDER_CANCELLED,
                RebyteExchanges.LICENCE_ISSUED,
                RebyteExchanges.LICENCE_REVOKED,
                DEVELOPER_ACTIVATED,
                RebyteExchanges.OFFER_HOLD_RELEASED,
                RebyteExchanges.LISTING_CREATED,
                RebyteExchanges.LISTING_REMOVED);

        assertThat(allRoutingKeys()).containsAll(excluded);
        assertThat(routed).doesNotContainAnyElementsOf(excluded);
    }

    @Test
    void aRejectedMessageLandsInItsOwnDeadLetterQueueOnly() {
        for (Map.Entry<String, Set<String>> entry : RabbitTopologyTest.ROUTING_MATRIX.entrySet()) {
            purgeAll();
            String queue = entry.getKey();
            String ownDlq = queue + ".dlq";
            String key = entry.getValue().iterator().next();
            publish(key);
            awaitDepth(queue, 1);

            rabbit.execute(channel -> {
                var response = channel.basicGet(queue, false);
                assertThat(response).as("message on %s", queue).isNotNull();
                channel.basicReject(response.getEnvelope().getDeliveryTag(), false);
                return null;
            });

            await(() -> depth(ownDlq) == 1, "message dead-lettered from " + queue + " to " + ownDlq);
            assertThat(depth(queue)).as(queue).isZero();
            for (String otherDlq : RabbitConfig.DEAD_LETTER_ORIGINS.keySet()) {
                if (!otherDlq.equals(ownDlq)) {
                    assertThat(depth(otherDlq)).as("%s must stay empty when %s rejects", otherDlq, queue).isZero();
                }
            }
        }
        purgeAll();
    }

    private static List<String> allRoutingKeys() {
        List<String> keys = new ArrayList<>();
        for (Field field : RebyteExchanges.class.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class && !field.getName().equals("MARKETPLACE_EVENTS")) {
                try {
                    keys.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        keys.add(RabbitConfig.EMAIL_SEND_ROUTING_KEY);
        keys.add(DEVELOPER_ACTIVATED);
        assertThat(keys).doesNotHaveDuplicates();
        return keys;
    }

    private byte[] receiveBody(String queue) {
        return rabbit.execute(channel -> {
            var response = channel.basicGet(queue, true);
            return response == null ? null : response.getBody();
        });
    }

    private void publish(String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        Message message = MessageBuilder.withBody(routingKey.getBytes(StandardCharsets.UTF_8))
                .andProperties(properties).build();
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, routingKey, message);
    }

    private void purgeAll() {
        RabbitTopologyTest.ROUTING_MATRIX.keySet().forEach(queue -> rabbitAdmin.purgeQueue(queue, false));
        RabbitConfig.DEAD_LETTER_ORIGINS.keySet().forEach(dlq -> rabbitAdmin.purgeQueue(dlq, false));
    }

    private long depth(String queue) {
        var info = rabbitAdmin.getQueueInfo(queue);
        assertThat(info).as("queue %s declared", queue).isNotNull();
        return info.getMessageCount();
    }

    private long totalDepth() {
        return RabbitTopologyTest.ROUTING_MATRIX.keySet().stream().mapToLong(this::depth).sum();
    }

    private void awaitDepth(String queue, int expected) {
        await(() -> depth(queue) == expected, queue + " depth " + expected);
    }

    private void awaitStableTotalDepth(int expected) {
        await(() -> totalDepth() == expected, "total depth " + expected);
        sleep(STABLE_MS);
        assertThat(totalDepth()).as("depth must not grow past the expected delivery count").isEqualTo(expected);
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(100);
        }
        assertThat(condition.getAsBoolean()).as("timed out waiting for %s", description).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
