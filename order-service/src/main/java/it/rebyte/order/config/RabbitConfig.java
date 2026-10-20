package it.rebyte.order.config;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import it.rebyte.commons.messaging.RebyteExchanges;

@Configuration
public class RabbitConfig {

    public static final String PAYMENT_RESULT_QUEUE = "order.payment-result";
    public static final String PAYMENT_RESULT_DLQ = "order.payment-result.dlq";
    public static final String LISTING_LIFECYCLE_QUEUE = "order.listing-lifecycle";
    public static final String LISTING_LIFECYCLE_DLQ = "order.listing-lifecycle.dlq";

    @Bean
    public TopicExchange marketplaceEventsExchange() {
        return new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
    }

    @Bean
    public Queue paymentResultQueue() {
        return new Queue(PAYMENT_RESULT_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", PAYMENT_RESULT_DLQ));
    }

    @Bean
    public Queue paymentResultDeadLetterQueue() {
        return new Queue(PAYMENT_RESULT_DLQ, true);
    }

    @Bean
    public Binding paymentResultBinding(@Qualifier("paymentResultQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with("payment.*");
    }

    @Bean
    public Queue listingLifecycleQueue() {
        return new Queue(LISTING_LIFECYCLE_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", LISTING_LIFECYCLE_DLQ));
    }

    @Bean
    public Queue listingLifecycleDeadLetterQueue() {
        return new Queue(LISTING_LIFECYCLE_DLQ, true);
    }

    /** Explicit keys only (D-01, D-05): listing.created, listing.favourited and listing.version.released never arrive here. */
    @Bean
    public Binding listingUpdatedBinding(@Qualifier("listingLifecycleQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_UPDATED);
    }

    @Bean
    public Binding listingRemovedBinding(@Qualifier("listingLifecycleQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_REMOVED);
    }
}
