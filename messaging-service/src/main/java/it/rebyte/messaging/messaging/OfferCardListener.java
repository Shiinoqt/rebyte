package it.rebyte.messaging.messaging;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.service.OfferCardService;

@Component
public class OfferCardListener {

    private final ObjectMapper mapper;
    private final OfferCardService cards;

    public OfferCardListener(ObjectMapper mapper, OfferCardService cards) {
        this.mapper = mapper;
        this.cards = cards;
    }

    @RabbitListener(queues = RabbitConfig.OFFER_CARD_QUEUE)
    public void onOfferEvent(Message message) {
        String cardEvent = switch (message.getMessageProperties().getReceivedRoutingKey()) {
            case RebyteExchanges.OFFER_CREATED -> "CREATED";
            case RebyteExchanges.OFFER_ACCEPTED -> "ACCEPTED";
            case RebyteExchanges.OFFER_REJECTED -> "REJECTED";
            case RebyteExchanges.OFFER_WITHDRAWN -> "WITHDRAWN";
            case RebyteExchanges.OFFER_EXPIRED -> "EXPIRED";
            case null, default -> throw new AmqpRejectAndDontRequeueException("Unsupported offer card routing key");
        };
        OfferEvent event;
        try {
            event = mapper.readValue(message.getBody(), OfferEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed offer card event", malformed);
        }
        if (event == null || missing(event.eventId()) || missing(event.offerId()) || missing(event.listingId())
                || missing(event.buyerSub()) || missing(event.sellerSub())) {
            throw new AmqpRejectAndDontRequeueException(
                    "Offer card requires eventId, offerId, listingId, buyerSub and sellerSub");
        }
        cards.apply(cardEvent, event);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
