package it.rebyte.payment.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Declares the {@link RebyteExchanges#MARKETPLACE_EVENTS} topic exchange
 * payment-service publishes to via {@link it.rebyte.payment.outbox.OutboxRelay}
 * (routing keys {@code payment.succeeded}/{@code payment.failed}/{@code
 * payment.expired}/{@code payment.refunded}).
 *
 * <p>order-service owns the {@code order.payment-result} queue and DLQ that
 * consume those routing keys — payment-service must not also declare or
 * administer that queue (CLAUDE.md: a service must never reach into another
 * service's owned infrastructure).
 */
@Configuration
public class RabbitConfig {

    @Bean
    public TopicExchange marketplaceEventsExchange() {
        return new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
    }
}
