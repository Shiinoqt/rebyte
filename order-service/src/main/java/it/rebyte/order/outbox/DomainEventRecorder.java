package it.rebyte.order.outbox;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.messaging.OfferLifecycleEvent;
import it.rebyte.order.messaging.OrderLifecycleEvent;

@Component
public class DomainEventRecorder {

    private final OutboxRepository repository;
    private final ObjectMapper mapper;

    public DomainEventRecorder(OutboxRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public void record(String eventType, Order order) {
        record(eventType, order.getId(), OrderLifecycleEvent.from(order));
    }

    public void record(String eventType, Offer offer) {
        record(eventType, offer.getId(), OfferLifecycleEvent.from(eventType, offer));
    }

    public void record(String eventType, String aggregateId, Object event) {
        try {
            String payload = mapper.writeValueAsString(event);
            repository.save(new OutboxEvent(eventType, aggregateId, payload));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Unable to serialize domain event", invalid);
        }
    }
}
