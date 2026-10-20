package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;

class RabbitTopologyTest {

    static final Map<String, Set<String>> ROUTING_MATRIX = Map.ofEntries(
            Map.entry(RabbitConfig.FAVOURITE_QUEUE,
                    Set.of(RebyteExchanges.LISTING_FAVOURITED, RebyteExchanges.LISTING_UNFAVOURITED)),
            Map.entry(RabbitConfig.FOLLOW_QUEUE,
                    Set.of(RebyteExchanges.USER_FOLLOWED, RebyteExchanges.USER_UNFOLLOWED)),
            Map.entry(RabbitConfig.PRICE_DROP_QUEUE, Set.of(RebyteExchanges.LISTING_UPDATED)),
            Map.entry(RabbitConfig.VERSION_RELEASED_QUEUE, Set.of(RebyteExchanges.LISTING_VERSION_RELEASED)),
            Map.entry(RabbitConfig.REVIEW_QUEUE, Set.of(RebyteExchanges.REVIEW_CREATED)),
            Map.entry(RabbitConfig.OFFER_NOTIFY_QUEUE,
                    Set.of(RebyteExchanges.OFFER_CREATED, RebyteExchanges.OFFER_ACCEPTED)),
            Map.entry(RabbitConfig.OFFER_CARD_QUEUE,
                    Set.of(RebyteExchanges.OFFER_CREATED, RebyteExchanges.OFFER_ACCEPTED,
                            RebyteExchanges.OFFER_REJECTED, RebyteExchanges.OFFER_WITHDRAWN,
                            RebyteExchanges.OFFER_EXPIRED)),
            Map.entry(RabbitConfig.ORDER_NOTIFY_QUEUE,
                    Set.of(RebyteExchanges.ORDER_PAID, RebyteExchanges.ORDER_SHIPPED,
                            RebyteExchanges.ORDER_DELIVERED, RebyteExchanges.ORDER_REFUNDED,
                            RebyteExchanges.ORDER_PROBLEM_OPENED, RebyteExchanges.ORDER_PROBLEM_RESOLVED)),
            Map.entry(RabbitConfig.EMAIL_SEND_QUEUE, Set.of(RabbitConfig.EMAIL_SEND_ROUTING_KEY)));

    private final RabbitConfig config = new RabbitConfig();
    private final List<Queue> queues = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();

    RabbitTopologyTest() throws Exception {
        collectBeans();
    }

    @Test
    void everyQueueIsBoundToExactlyItsExplicitRoutingKeys() {
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Binding binding : bindings) {
            assertThat(binding.getExchange()).isEqualTo(RebyteExchanges.MARKETPLACE_EVENTS);
            assertThat(binding.getDestination()).isNotNull();
            actual.computeIfAbsent(binding.getDestination(), name -> new TreeSet<>())
                    .add(binding.getRoutingKey());
        }

        assertThat(actual).isEqualTo(new TreeMap<>(ROUTING_MATRIX));
    }

    @Test
    void noBindingUsesAWildcard() {
        assertThat(bindings).extracting(Binding::getRoutingKey)
                .noneMatch(key -> key.contains("*") || key.contains("#"));
    }

    @Test
    void everyConsumerQueueDeadLettersToItsOwnDlqThroughTheDefaultExchange() {
        Map<String, Queue> byName = queuesByName();

        assertThat(RabbitConfig.DEAD_LETTER_ORIGINS).hasSize(ROUTING_MATRIX.size());
        RabbitConfig.DEAD_LETTER_ORIGINS.forEach((dlq, origin) -> {
            assertThat(dlq).isEqualTo(origin + ".dlq");
            Queue queue = byName.get(origin);
            assertThat(queue).as(origin).isNotNull();
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments())
                    .containsEntry("x-dead-letter-exchange", "")
                    .containsEntry("x-dead-letter-routing-key", dlq);

            Queue deadLetter = byName.get(dlq);
            assertThat(deadLetter).as(dlq).isNotNull();
            assertThat(deadLetter.isDurable()).isTrue();
            assertThat(deadLetter.getArguments()).isEmpty();
        });
        assertThat(byName.keySet()).hasSize(ROUTING_MATRIX.size() * 2);
    }

    @Test
    void onlyTheEmailQueueDeclaresSingleActiveConsumer() {
        queuesByName().forEach((name, queue) -> {
            if (name.equals(RabbitConfig.EMAIL_SEND_QUEUE)) {
                assertThat(queue.getArguments()).containsEntry("x-single-active-consumer", true);
            } else {
                assertThat(queue.getArguments()).doesNotContainKey("x-single-active-consumer");
            }
        });
    }

    @Test
    void exchangeIsADurableTopicExchange() {
        TopicExchange exchange = config.marketplaceEventsExchange();

        assertThat(exchange.getName()).isEqualTo(RebyteExchanges.MARKETPLACE_EVENTS);
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
    }

    private Map<String, Queue> queuesByName() {
        Map<String, Queue> byName = new HashMap<>();
        queues.forEach(queue -> byName.put(queue.getName(), queue));
        assertThat(byName).hasSameSizeAs(queues);
        return byName;
    }

    private void collectBeans() throws Exception {
        TopicExchange exchange = config.marketplaceEventsExchange();
        Map<String, Queue> byBeanName = new HashMap<>();
        for (Method method : RabbitConfig.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class) && method.getReturnType() == Queue.class) {
                Queue queue = (Queue) invoke(method);
                byBeanName.put(method.getName(), queue);
                queues.add(queue);
            }
        }
        for (Method method : RabbitConfig.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class) && method.getReturnType() == Binding.class) {
                Qualifier qualifier = method.getParameters()[0].getAnnotation(Qualifier.class);
                bindings.add((Binding) invoke(method, byBeanName.get(qualifier.value()), exchange));
            }
        }
    }

    private Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(config, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }
}
