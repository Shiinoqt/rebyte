package it.rebyte.messaging.messaging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.service.FavouriteNotificationService;

@Component
public class FavouriteListener {

    private static final Logger log = LoggerFactory.getLogger(FavouriteListener.class);

    private final ObjectReader reader;
    private final FavouriteNotificationService notifications;

    public FavouriteListener(ObjectMapper mapper, FavouriteNotificationService notifications) {
        this.reader = mapper.readerFor(FavouriteEvent.class).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitConfig.FAVOURITE_QUEUE)
    public void onEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        FavouriteEvent event;
        try {
            event = reader.readValue(message.getBody());
        } catch (IOException malformed) {
            throw reject(routingKey);
        }
        if (event == null || !validId(event.eventId()) || !validId(event.userSub())
                || !validId(event.sellerSub()) || !validId(event.listingId()) || event.userSub().equals(event.sellerSub())
                || event.occurredAt() == null
                || (event.relationshipSequence() != null && event.relationshipSequence() <= 0)) {
            throw reject(routingKey);
        }
        if (RebyteExchanges.LISTING_FAVOURITED.equals(routingKey)) {
            notifications.favourited(event);
        } else if (RebyteExchanges.LISTING_UNFAVOURITED.equals(routingKey)) {
            notifications.unfavourited(event);
        } else {
            throw reject(routingKey);
        }
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64
                && value.chars().allMatch(c -> c <= 0x7F);
    }

    private AmqpRejectAndDontRequeueException reject(String routingKey) {
        log.warn("Rejecting invalid favourite event routingKey={}", routingKey);
        return new AmqpRejectAndDontRequeueException("Invalid favourite event");
    }
}
