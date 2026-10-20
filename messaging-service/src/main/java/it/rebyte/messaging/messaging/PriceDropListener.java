package it.rebyte.messaging.messaging;

import java.io.IOException;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.service.PriceDropService;

@Component
public class PriceDropListener {

    private final ObjectMapper mapper;
    private final PriceDropService notifications;

    public PriceDropListener(ObjectMapper mapper, PriceDropService notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitConfig.PRICE_DROP_QUEUE)
    public void onEvent(Message message) {
        ListingPriceEvent event;
        try {
            event = mapper.readValue(message.getBody(), ListingPriceEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Invalid listing price event", malformed);
        }
        if (event == null || event.listingId() == null || event.listingId().isBlank() || event.occurredAt() == null) {
            throw new AmqpRejectAndDontRequeueException("Invalid listing price event");
        }
        notifications.fanOut(event);
    }
}
