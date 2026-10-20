package it.rebyte.messaging.messaging;

import java.io.IOException;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.OrderNotificationEvent;
import it.rebyte.messaging.service.OrderNotificationService;

@Component
public class OrderNotificationListener {

    private final ObjectMapper mapper;
    private final OrderNotificationService notifications;

    public OrderNotificationListener(ObjectMapper mapper, OrderNotificationService notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_NOTIFY_QUEUE)
    public void onOrderEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        OrderNotificationEvent event = switch (routingKey) {
            case RebyteExchanges.ORDER_PAID -> OrderNotificationEvent.PAID;
            case RebyteExchanges.ORDER_SHIPPED -> OrderNotificationEvent.SHIPPED;
            case RebyteExchanges.ORDER_DELIVERED -> OrderNotificationEvent.DELIVERED;
            case RebyteExchanges.ORDER_REFUNDED -> OrderNotificationEvent.REFUNDED;
            case RebyteExchanges.ORDER_PROBLEM_OPENED -> OrderNotificationEvent.PROBLEM_OPENED;
            case RebyteExchanges.ORDER_PROBLEM_RESOLVED -> OrderNotificationEvent.PROBLEM_RESOLVED;
            case null, default -> throw new AmqpRejectAndDontRequeueException("Unsupported order routing key");
        };

        OrderEvent payload;
        try {
            payload = mapper.readValue(message.getBody(), OrderEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed order notification event", malformed);
        }
        if (payload == null || missing(payload.eventId()) || missing(payload.orderId())
                || missing(payload.buyerSub()) || missing(payload.sellerSub())) {
            throw new AmqpRejectAndDontRequeueException("Order notification requires eventId, orderId, buyerSub and sellerSub");
        }
        notifications.apply(event, payload);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
