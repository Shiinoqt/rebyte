package it.rebyte.catalog.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Declares the {@link RebyteExchanges#MARKETPLACE_EVENTS} topic exchange
 * this service both publishes to (via {@link it.rebyte.catalog.outbox.OutboxRelay})
 * and consumes from, plus the indexer's own queue and its three bindings
 * (docs/architecture.md §5's naming: {@code search.listing-changed}).
 *
 * <p>user-service already declares this exchange with the same durability
 * and type — redeclaring it identically here is normal per-service AMQP
 * practice, not a duplicate: whichever service starts first actually
 * creates it, every later declaration with matching parameters is a no-op.
 * A mismatched redeclaration would fail startup with
 * {@code PRECONDITION_FAILED} rather than silently diverging.
 *
 * <p>Bound to exactly the three {@code listing.*} keys the indexer handles
 * from the start (created/updated/removed) — not a single wildcard
 * binding, which would also match {@code listing.favourited} and {@code
 * listing.unfavourited}, keys this queue is not meant to receive.
 */
@Configuration
public class RabbitConfig {

    /** Queue name for the indexer's {@code listing.*} consumer. */
    public static final String LISTING_CHANGED_QUEUE = "search.listing-changed";
    public static final String LISTING_CACHE_EVICTION_QUEUE = "cache.listing-changed";

    @Bean
    public SimpleRabbitListenerContainerFactory listingIndexerContainerFactory(ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(50);
        factory.setPrefetchCount(50);
        factory.setReceiveTimeout(200L);
        factory.setBatchReceiveTimeout(200L);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    @Bean
    public TopicExchange marketplaceEventsExchange() {
        return new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
    }

    @Bean
    public Queue listingChangedQueue() {
        return new Queue(LISTING_CHANGED_QUEUE, true);
    }

    @Bean
    public Queue listingCacheEvictionQueue() {
        return new Queue(LISTING_CACHE_EVICTION_QUEUE, true);
    }

    @Bean
    public Binding listingCreatedBinding(@Qualifier("listingChangedQueue") Queue listingChangedQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(listingChangedQueue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_CREATED);
    }

    @Bean
    public Binding listingUpdatedBinding(@Qualifier("listingChangedQueue") Queue listingChangedQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(listingChangedQueue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_UPDATED);
    }

    @Bean
    public Binding listingRemovedBinding(@Qualifier("listingChangedQueue") Queue listingChangedQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(listingChangedQueue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_REMOVED);
    }

    @Bean
    public Binding listingCacheCreatedBinding(@Qualifier("listingCacheEvictionQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_CREATED);
    }

    @Bean
    public Binding listingCacheUpdatedBinding(@Qualifier("listingCacheEvictionQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_UPDATED);
    }

    @Bean
    public Binding listingCacheRemovedBinding(@Qualifier("listingCacheEvictionQueue") Queue queue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_REMOVED);
    }
    public static final String ORDER_LIFECYCLE_QUEUE = "catalog.order-lifecycle";
    public static final String ORDER_LIFECYCLE_DLQ = "catalog.order-lifecycle.dlq";

    @Bean
    public Queue orderLifecycleQueue() {
        return new Queue(ORDER_LIFECYCLE_QUEUE, true, false, false, java.util.Map.of(
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", ORDER_LIFECYCLE_DLQ));
    }

    @Bean
    public Queue orderLifecycleDeadLetterQueue() { return new Queue(ORDER_LIFECYCLE_DLQ, true); }

    @Bean
    public Binding orderCancelledBinding(@Qualifier("orderLifecycleQueue") Queue orderLifecycleQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(orderLifecycleQueue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_CANCELLED);
    }

    @Bean
    public Binding orderRefundedBinding(@Qualifier("orderLifecycleQueue") Queue orderLifecycleQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(orderLifecycleQueue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_REFUNDED);
    }

    @Bean
    public Binding offerHoldReleasedBinding(@Qualifier("orderLifecycleQueue") Queue orderLifecycleQueue,
            TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(orderLifecycleQueue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_HOLD_RELEASED);
    }
}
