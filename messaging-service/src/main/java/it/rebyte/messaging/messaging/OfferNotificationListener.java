package it.rebyte.messaging.messaging;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.service.OfferNotificationService;

@Component
public class OfferNotificationListener {

    private final ObjectMapper mapper;
    private final OfferNotificationService notifications;

    public OfferNotificationListener(ObjectMapper mapper, OfferNotificationService notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitConfig.OFFER_NOTIFY_QUEUE)
    public void onOfferEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        if (!RebyteExchanges.OFFER_CREATED.equals(routingKey) && !RebyteExchanges.OFFER_ACCEPTED.equals(routingKey)) {
            throw new AmqpRejectAndDontRequeueException("Unsupported offer notification routing key");
        }
        OfferEvent event;
        try {
            event = mapper.readValue(message.getBody(), OfferEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed offer notification event", malformed);
        }
        if (event == null || missing(event.eventId()) || missing(event.offerId()) || missing(event.listingId())
                || missing(event.buyerSub()) || missing(event.sellerSub())) {
            throw new AmqpRejectAndDontRequeueException(
                    "Offer notification requires eventId, offerId, listingId, buyerSub and sellerSub");
        }
        if (RebyteExchanges.OFFER_CREATED.equals(routingKey)) {
            notifications.received(event);
        } else {
            notifications.accepted(event);
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
