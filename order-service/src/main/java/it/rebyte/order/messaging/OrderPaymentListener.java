package it.rebyte.order.messaging;

import java.io.IOException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.service.OrderPaymentTransitionService;

@Component
public class OrderPaymentListener {

    private final ObjectMapper mapper;
    private final OrderPaymentTransitionService transitions;

    public OrderPaymentListener(ObjectMapper mapper, OrderPaymentTransitionService transitions) {
        this.mapper = mapper;
        this.transitions = transitions;
    }

    @RabbitListener(queues = RabbitConfig.PAYMENT_RESULT_QUEUE)
    public void receive(Message message) throws IOException {
        PaymentResultEvent event = mapper.readValue(message.getBody(), PaymentResultEvent.class);
        transitions.apply(message.getMessageProperties().getReceivedRoutingKey(), event);
    }
}
