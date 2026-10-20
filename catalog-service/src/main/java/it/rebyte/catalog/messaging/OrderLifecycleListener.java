package it.rebyte.catalog.messaging;

import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.rebyte.catalog.config.RabbitConfig;
import it.rebyte.catalog.service.ListingReservationService;

@Component
public class OrderLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleListener.class);
    private final ObjectMapper mapper;
    private final ListingReservationService reservations;
    public OrderLifecycleListener(ObjectMapper mapper, ListingReservationService reservations) {
        this.mapper = mapper;
        this.reservations = reservations;
    }

    /** order.cancelled, order.refunded and offer.hold.released carry a listingId and the owning reservationId when release is requested. */
    public record OrderReleaseEvent(String listingId, String reservationId, Instant occurredAt, String listingType) {
        public OrderReleaseEvent(String listingId, String reservationId, Instant occurredAt) {
            this(listingId, reservationId, occurredAt, null);
        }
    }

    @RabbitListener(queues = RabbitConfig.ORDER_LIFECYCLE_QUEUE)
    public void onOrderReleased(Message message) {
        OrderReleaseEvent event;
        try {
            event = mapper.readValue(message.getBody(), OrderReleaseEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed order release event", malformed);
        }
        // Software listings are never reserved, so a software order's lifecycle event has nothing to release.
        if (event != null && "SOFTWARE".equals(event.listingType())) {
            return;
        }
        if (event == null || event.listingId() == null || event.listingId().isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Order release requires listingId");
        }
        if (event.reservationId() == null || event.reservationId().isBlank()) {
            log.info("RELEASE_NOT_REQUESTED listingId={}", event.listingId());
            return;
        }
        reservations.release(event.listingId(), event.reservationId());
    }
}
