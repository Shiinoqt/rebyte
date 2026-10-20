package it.rebyte.order.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.rabbitmq.client.GetResponse;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.config.RabbitConfig;

@RestController
public class OrderDeadLetterController {

    private static final Logger log = LoggerFactory.getLogger(OrderDeadLetterController.class);

    /**
     * Each saga DLQ mapped to the routing-key prefix its own listener handles. Replaying a
     * listing.* dead letter republishes it to marketplace.events, so catalog-service's own
     * listing.* consumers (the search indexer's sequence guard, favourites cache eviction) also
     * receive the duplicate — both already tolerate a redelivered event.
     */
    private static final Map<String, String> DLQ_ROUTING_PREFIXES = Map.of(
            RabbitConfig.PAYMENT_RESULT_DLQ, "payment.",
            RabbitConfig.LISTING_LIFECYCLE_DLQ, "listing.");

    private final RabbitTemplate rabbit;

    public OrderDeadLetterController(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @PostMapping("/admin/orders/dead-letters/{queue}/replay")
    public ResponseEntity<Void> replayOne(@AuthenticationPrincipal Jwt jwt, @PathVariable String queue) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        if (groups == null || !groups.contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String expectedPrefix = DLQ_ROUTING_PREFIXES.get(queue);
        if (expectedPrefix == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String auditId = UUID.randomUUID().toString();
        Boolean replayed = rabbit.execute(channel -> {
            GetResponse delivery = channel.basicGet(queue, false);
            if (delivery == null) {
                return false;
            }
            String routingKey = originalRoutingKey(delivery.getProps().getHeaders(), expectedPrefix);
            if (routingKey == null) {
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
                return null;
            }
            long deliveryTag = delivery.getEnvelope().getDeliveryTag();
            try {
                channel.confirmSelect();
                channel.basicPublish(RebyteExchanges.MARKETPLACE_EVENTS, routingKey, delivery.getProps(),
                        delivery.getBody());
                channel.waitForConfirmsOrDie(5000);
            } catch (Exception failure) {
                // The caching connection factory returns this channel to its cache still open, so
                // without an explicit nack the pulled dead letter would stay unacked and invisible
                // in the DLQ.
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                channel.basicNack(deliveryTag, false, true);
                throw failure;
            }
            channel.basicAck(deliveryTag, false);
            return true;
        });
        if (replayed == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dead letter has no original routing key for " + queue + "; left parked");
        }
        if (!replayed) {
            return ResponseEntity.noContent().build();
        }
        log.info("Replayed one order dead letter from {}; auditId={}", queue, auditId);
        return ResponseEntity.ok().header("X-Audit-Id", auditId).build();
    }

    /**
     * The listener takes the event type from the received routing key, so a replay must go
     * back through the exchange under the key the message was first published with.
     */
    private static String originalRoutingKey(Map<String, Object> headers, String expectedPrefix) {
        if (headers == null || !(headers.get("x-death") instanceof List<?> deaths)) {
            return null;
        }
        for (Object death : deaths) {
            if (death instanceof Map<?, ?> entry
                    && RebyteExchanges.MARKETPLACE_EVENTS.equals(String.valueOf(entry.get("exchange")))
                    && entry.get("routing-keys") instanceof List<?> keys && !keys.isEmpty()) {
                String key = String.valueOf(keys.get(0));
                if (key.startsWith(expectedPrefix)) {
                    return key;
                }
            }
        }
        return null;
    }
}
