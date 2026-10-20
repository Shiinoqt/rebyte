package it.rebyte.messaging.config;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Declares {@link RebyteExchanges#MARKETPLACE_EVENTS} and every messaging
 * consumer queue, each with a dead-letter queue named {@code <queue>.dlq}
 * reached through the default exchange.
 *
 * <p>Every queue is bound by explicit routing keys only. A wildcard such as
 * {@code order.*} would not match the three-word keys
 * {@code order.problem.opened} and {@code offer.hold.released} reliably and
 * would also pull in keys no consumer handles.
 *
 * <p>Deliberately unbound: {@code order.cancelled}, {@code licence.issued},
 * {@code licence.revoked}, {@code user.developer_activated} (D-12: nobody is
 * notified), {@code offer.hold.released} and {@code listing.created} /
 * {@code listing.removed} (D-20: no consumer for stale targets).
 *
 * <p>{@link #EMAIL_SEND_QUEUE} declares {@code x-single-active-consumer}, which
 * must be identical on every instance; changing it on an existing broker
 * requires draining and recreating the queue.
 */
@Configuration
public class RabbitConfig {

    public static final String EMAIL_SEND_ROUTING_KEY = "email.send";
    public static final String REPLAY_ROUTING_KEY_HEADER = "x-rebyte-original-routing-key";
    public static final String FAVOURITE_QUEUE = "notify.favourite";
    public static final String FAVOURITE_DLQ = FAVOURITE_QUEUE + ".dlq";
    public static final String FOLLOW_QUEUE = "notify.follow";
    public static final String FOLLOW_DLQ = FOLLOW_QUEUE + ".dlq";
    public static final String PRICE_DROP_QUEUE = "notify.price-drop";
    public static final String PRICE_DROP_DLQ = PRICE_DROP_QUEUE + ".dlq";
    public static final String VERSION_RELEASED_QUEUE = "notify.version-released";
    public static final String VERSION_RELEASED_DLQ = VERSION_RELEASED_QUEUE + ".dlq";
    public static final String REVIEW_QUEUE = "notify.review";
    public static final String REVIEW_DLQ = REVIEW_QUEUE + ".dlq";
    public static final String OFFER_NOTIFY_QUEUE = "notify.offer";
    public static final String OFFER_NOTIFY_DLQ = OFFER_NOTIFY_QUEUE + ".dlq";
    public static final String OFFER_CARD_QUEUE = "chat.offer-card";
    public static final String OFFER_CARD_DLQ = OFFER_CARD_QUEUE + ".dlq";
    public static final String ORDER_NOTIFY_QUEUE = "notify.order";
    public static final String ORDER_NOTIFY_DLQ = ORDER_NOTIFY_QUEUE + ".dlq";
    public static final String EMAIL_SEND_QUEUE = "messaging.email-send";
    public static final String EMAIL_SEND_DLQ = EMAIL_SEND_QUEUE + ".dlq";
    /** Each dead-letter queue mapped to the queue whose rejected messages it holds. */
    public static final Map<String, String> DEAD_LETTER_ORIGINS = Map.ofEntries(
            Map.entry(FAVOURITE_DLQ, FAVOURITE_QUEUE),
            Map.entry(FOLLOW_DLQ, FOLLOW_QUEUE),
            Map.entry(PRICE_DROP_DLQ, PRICE_DROP_QUEUE),
            Map.entry(VERSION_RELEASED_DLQ, VERSION_RELEASED_QUEUE),
            Map.entry(REVIEW_DLQ, REVIEW_QUEUE),
            Map.entry(OFFER_NOTIFY_DLQ, OFFER_NOTIFY_QUEUE),
            Map.entry(OFFER_CARD_DLQ, OFFER_CARD_QUEUE),
            Map.entry(ORDER_NOTIFY_DLQ, ORDER_NOTIFY_QUEUE),
            Map.entry(EMAIL_SEND_DLQ, EMAIL_SEND_QUEUE));

    @Bean
    public RabbitListenerRetrySettingsCustomizer noRetryOnRejects() {
        return settings -> settings.getExceptionExcludes().addAll(List.of(AmqpRejectAndDontRequeueException.class,
                IllegalArgumentException.class, JsonProcessingException.class,
                org.springframework.amqp.support.converter.MessageConversionException.class,
                org.springframework.messaging.converter.MessageConversionException.class));
    }

    @Bean
    public ContainerCustomizer<SimpleMessageListenerContainer> replayRoutingKeyCustomizer() {
        return container -> container.setAfterReceivePostProcessors(message -> {
            var properties = message.getMessageProperties();
            String queue = properties.getConsumerQueue();
            // The default exchange preserves replay isolation but replaces the event routing key.
            if (!"".equals(properties.getReceivedExchange())
                    || !DEAD_LETTER_ORIGINS.containsValue(queue)
                    || !queue.equals(properties.getReceivedRoutingKey())) {
                return message;
            }
            String routingKey = replayRoutingKey(properties.getHeaders(), queue);
            if (routingKey != null) {
                properties.setReceivedRoutingKey(routingKey);
            }
            return message;
        });
    }

    public static String replayRoutingKey(Map<String, Object> headers, String queue) {
        if (headers == null) {
            return null;
        }
        Object saved = headers.get(REPLAY_ROUTING_KEY_HEADER);
        if (saved != null && !String.valueOf(saved).isBlank() && !queue.equals(String.valueOf(saved))) {
            return String.valueOf(saved);
        }
        if (headers.get("x-death") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> death && queue.equals(String.valueOf(death.get("queue")))
                        && death.get("routing-keys") instanceof List<?> keys) {
                    for (Object key : keys) {
                        String routingKey = String.valueOf(key);
                        if (!queue.equals(routingKey) && !routingKey.isBlank()) {
                            return routingKey;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Bean
    public TopicExchange marketplaceEventsExchange() {
        return new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
    }

    @Bean
    public Queue favouriteQueue() {
        return deadLetteringQueue(FAVOURITE_QUEUE, FAVOURITE_DLQ);
    }

    @Bean
    public Queue favouriteDeadLetterQueue() {
        return new Queue(FAVOURITE_DLQ, true);
    }

    @Bean
    public Binding favouriteListingFavouritedBinding(@Qualifier("favouriteQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_FAVOURITED);
    }

    @Bean
    public Binding favouriteListingUnfavouritedBinding(@Qualifier("favouriteQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_UNFAVOURITED);
    }

    @Bean
    public Queue followQueue() {
        return deadLetteringQueue(FOLLOW_QUEUE, FOLLOW_DLQ);
    }

    @Bean
    public Queue followDeadLetterQueue() {
        return new Queue(FOLLOW_DLQ, true);
    }

    @Bean
    public Binding followUserFollowedBinding(@Qualifier("followQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.USER_FOLLOWED);
    }

    @Bean
    public Binding followUserUnfollowedBinding(@Qualifier("followQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.USER_UNFOLLOWED);
    }

    @Bean
    public Queue priceDropQueue() {
        return deadLetteringQueue(PRICE_DROP_QUEUE, PRICE_DROP_DLQ);
    }

    @Bean
    public Queue priceDropDeadLetterQueue() {
        return new Queue(PRICE_DROP_DLQ, true);
    }

    @Bean
    public Binding priceDropListingUpdatedBinding(@Qualifier("priceDropQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_UPDATED);
    }

    @Bean
    public Queue versionReleasedQueue() {
        return deadLetteringQueue(VERSION_RELEASED_QUEUE, VERSION_RELEASED_DLQ);
    }

    @Bean
    public Queue versionReleasedDeadLetterQueue() {
        return new Queue(VERSION_RELEASED_DLQ, true);
    }

    @Bean
    public Binding versionReleasedListingVersionReleasedBinding(@Qualifier("versionReleasedQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.LISTING_VERSION_RELEASED);
    }

    @Bean
    public Queue reviewQueue() {
        return deadLetteringQueue(REVIEW_QUEUE, REVIEW_DLQ);
    }

    @Bean
    public Queue reviewDeadLetterQueue() {
        return new Queue(REVIEW_DLQ, true);
    }

    @Bean
    public Binding reviewReviewCreatedBinding(@Qualifier("reviewQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.REVIEW_CREATED);
    }

    @Bean
    public Queue offerNotifyQueue() {
        return deadLetteringQueue(OFFER_NOTIFY_QUEUE, OFFER_NOTIFY_DLQ);
    }

    @Bean
    public Queue offerNotifyDeadLetterQueue() {
        return new Queue(OFFER_NOTIFY_DLQ, true);
    }

    @Bean
    public Binding offerNotifyOfferCreatedBinding(@Qualifier("offerNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_CREATED);
    }

    @Bean
    public Binding offerNotifyOfferAcceptedBinding(@Qualifier("offerNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_ACCEPTED);
    }

    @Bean
    public Queue offerCardQueue() {
        return deadLetteringQueue(OFFER_CARD_QUEUE, OFFER_CARD_DLQ);
    }

    @Bean
    public Queue offerCardDeadLetterQueue() {
        return new Queue(OFFER_CARD_DLQ, true);
    }

    @Bean
    public Binding offerCardOfferCreatedBinding(@Qualifier("offerCardQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_CREATED);
    }

    @Bean
    public Binding offerCardOfferAcceptedBinding(@Qualifier("offerCardQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_ACCEPTED);
    }

    @Bean
    public Binding offerCardOfferRejectedBinding(@Qualifier("offerCardQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_REJECTED);
    }

    @Bean
    public Binding offerCardOfferWithdrawnBinding(@Qualifier("offerCardQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_WITHDRAWN);
    }

    @Bean
    public Binding offerCardOfferExpiredBinding(@Qualifier("offerCardQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.OFFER_EXPIRED);
    }

    @Bean
    public Queue orderNotifyQueue() {
        return deadLetteringQueue(ORDER_NOTIFY_QUEUE, ORDER_NOTIFY_DLQ);
    }

    @Bean
    public Queue orderNotifyDeadLetterQueue() {
        return new Queue(ORDER_NOTIFY_DLQ, true);
    }

    @Bean
    public Binding orderNotifyOrderPaidBinding(@Qualifier("orderNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_PAID);
    }

    @Bean
    public Binding orderNotifyOrderShippedBinding(@Qualifier("orderNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_SHIPPED);
    }

    @Bean
    public Binding orderNotifyOrderDeliveredBinding(@Qualifier("orderNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_DELIVERED);
    }

    @Bean
    public Binding orderNotifyOrderRefundedBinding(@Qualifier("orderNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_REFUNDED);
    }

    @Bean
    public Binding orderNotifyOrderProblemOpenedBinding(@Qualifier("orderNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_PROBLEM_OPENED);
    }

    @Bean
    public Binding orderNotifyOrderProblemResolvedBinding(@Qualifier("orderNotifyQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(RebyteExchanges.ORDER_PROBLEM_RESOLVED);
    }

    @Bean
    public Queue emailSendQueue() {
        return new Queue(EMAIL_SEND_QUEUE, true, false, false, Map.of(
                "x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", EMAIL_SEND_DLQ,
                "x-single-active-consumer", true));
    }

    @Bean
    public Queue emailSendDeadLetterQueue() {
        return new Queue(EMAIL_SEND_DLQ, true);
    }

    @Bean
    public Binding emailSendBinding(@Qualifier("emailSendQueue") Queue queue, TopicExchange marketplaceEventsExchange) {
        return BindingBuilder.bind(queue).to(marketplaceEventsExchange).with(EMAIL_SEND_ROUTING_KEY);
    }

    private static Queue deadLetteringQueue(String name, String deadLetterQueue) {
        return new Queue(name, true, false, false, Map.of(
                "x-dead-letter-exchange", "",
                "x-dead-letter-routing-key", deadLetterQueue));
    }
}
