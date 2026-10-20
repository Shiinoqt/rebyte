package it.rebyte.user.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Declares the topic exchange named by {@link RebyteExchanges#MARKETPLACE_EVENTS}
 * that this service publishes to (D-24). user-service is the publisher
 * here, so it owns the exchange declaration; each consumer service
 * declares its own queue and binding, never the exchange itself.
 *
 * <p>Durable, non-auto-delete. Redeclaring this exchange elsewhere with a
 * different type or durability fails startup with
 * {@code PRECONDITION_FAILED} rather than silently creating a second
 * exchange — demonstrated once during this plan's execution, see the plan
 * SUMMARY for the observed failure.
 *
 * <p>This class also declares user-service's OWN queue and binding for
 * the {@code review.created} routing key it consumes (D-24, D-47): the
 * publisher declares the exchange, each consumer declares its own queue
 * and binding — this is the consumer half of that rule, for the same
 * service that also happens to be this exchange's publisher. The queue
 * dead-letters through the default exchange into
 * {@link #REVIEW_CREATED_DLQ}, which receives the payloads the listener
 * rejects as invalid.
 */
@Configuration
public class RabbitConfig {

    /** Queue name for this service's own {@code review.created} consumer (D-24). */
    public static final String REVIEW_CREATED_QUEUE = "user-service.review-created";
    public static final String REVIEW_CREATED_DLQ = "user-service.review-created.dlq";
    public static final String ORDER_SALES_QUEUE = "user-service.order-sales";
    public static final String ORDER_SALES_DLQ = "user-service.order-sales.dlq";

    @Bean
    public TopicExchange marketplaceEventsExchange() {
        return new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
    }

    @Bean
    public Queue reviewCreatedQueue() {
        return new Queue(REVIEW_CREATED_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", REVIEW_CREATED_DLQ));
    }

    @Bean
    public Queue reviewCreatedDeadLetterQueue() {
        return new Queue(REVIEW_CREATED_DLQ, true);
    }

    @Bean
    public Binding reviewCreatedBinding(@Qualifier("reviewCreatedQueue") Queue reviewCreatedQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(reviewCreatedQueue).to(marketplaceEventsExchange).with(RebyteExchanges.REVIEW_CREATED);
    }

    @Bean
    public Queue orderSalesQueue() {
        return new Queue(ORDER_SALES_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", ORDER_SALES_DLQ));
    }

    @Bean
    public Queue orderSalesDeadLetterQueue() {
        return new Queue(ORDER_SALES_DLQ, true);
    }

    @Bean
    public Binding orderDeliveredBinding(@Qualifier("orderSalesQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_DELIVERED);
    }

    @Bean
    public Binding orderRefundedBinding(@Qualifier("orderSalesQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_REFUNDED);
    }
}
