package it.rebyte.order.messaging;

import java.io.IOException;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.service.OfferListingSyncService;

@Component
public class ListingLifecycleListener {

    private final ObjectMapper mapper;
    private final OfferListingSyncService sync;

    public ListingLifecycleListener(ObjectMapper mapper, OfferListingSyncService sync) {
        this.mapper = mapper;
        this.sync = sync;
    }

    @RabbitListener(queues = RabbitConfig.LISTING_LIFECYCLE_QUEUE)
    public void receive(Message message) {
        ListingLifecycleEvent event;
        try {
            event = mapper.readValue(message.getBody(), ListingLifecycleEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed listing lifecycle event", malformed);
        }
        if (event.listingId() == null || event.listingId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Listing lifecycle event missing listingId");
        }
        sync.onListingChanged(message.getMessageProperties().getReceivedRoutingKey(), event.listingId());
    }
}
