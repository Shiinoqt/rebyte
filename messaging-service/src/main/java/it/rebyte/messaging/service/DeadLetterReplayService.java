package it.rebyte.messaging.service;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.rabbitmq.client.ReturnListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.error.DeadLetterQueueNotFoundException;

/**
 * Replaying into the originating queue keeps sibling queues bound to the same
 * routing key, such as notify.offer and chat.offer-card, from seeing the event twice.
 */
@Service
public class DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);
    private final RabbitTemplate rabbit;

    public DeadLetterReplayService(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public ReplayResult replayOne(String queue, String adminSub) {
        String auditId = UUID.randomUUID().toString();
        String origin = RabbitConfig.DEAD_LETTER_ORIGINS.get(queue);
        if (origin == null) {
            log.warn("auditId={} adminSub={} queue={} messageId={} outcome=unknown-queue", auditId, adminSub, queue, null);
            throw new DeadLetterQueueNotFoundException();
        }
        AtomicReference<String> messageId = new AtomicReference<>();
        AtomicReference<String> eventKey = new AtomicReference<>();
        try {
            Boolean replayed = rabbit.execute(channel -> {
                var delivery = channel.basicGet(queue, false);
                if (delivery == null) {
                    return false;
                }
                messageId.set(delivery.getProps().getMessageId());
                long tag = delivery.getEnvelope().getDeliveryTag();
                AtomicBoolean returned = new AtomicBoolean();
                ReturnListener returnListener = (code, text, exchange, key, props, body) -> returned.set(true);
                try {
                    channel.addReturnListener(returnListener);
                    var properties = delivery.getProps();
                    String routingKey = RabbitConfig.replayRoutingKey(properties.getHeaders(), origin);
                    eventKey.set(routingKey == null ? delivery.getEnvelope().getRoutingKey() : routingKey);
                    if (routingKey != null) {
                        // RabbitMQ 4.x rebuilds x-death after another rejection, losing the event key.
                        var headers = new HashMap<>(properties.getHeaders());
                        headers.put(RabbitConfig.REPLAY_ROUTING_KEY_HEADER, routingKey);
                        properties = properties.builder().headers(headers).build();
                    }
                    channel.confirmSelect();
                    channel.basicPublish("", origin, true, properties, delivery.getBody());
                    channel.waitForConfirmsOrDie(5000);
                    // A confirm alone also acknowledges a dropped publication when its queue is absent.
                    if (returned.get()) {
                        throw new IOException("Dead-letter replay was returned by the broker");
                    }
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    // A cached open channel would otherwise leave the pulled letter unacked and invisible.
                    try {
                        channel.basicNack(tag, false, true);
                    } catch (Exception nackFailure) {
                        failure.addSuppressed(nackFailure);
                    }
                    throw failure;
                } finally {
                    channel.removeReturnListener(returnListener);
                }
                channel.basicAck(tag, false);
                return true;
            });
            boolean moved = Boolean.TRUE.equals(replayed);
            log.info("auditId={} adminSub={} queue={} messageId={} routingKey={} outcome={}",
                    auditId, adminSub, queue, messageId.get(), eventKey.get(), moved ? "replayed" : "empty");
            return new ReplayResult(moved, auditId);
        } catch (RuntimeException failure) {
            log.warn("auditId={} adminSub={} queue={} messageId={} routingKey={} outcome=failed failure={}",
                    auditId, adminSub, queue, messageId.get(), eventKey.get(), failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public record ReplayResult(boolean replayed, String auditId) {
    }
}
