package it.rebyte.user.messaging;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.user.config.RabbitConfig;
import it.rebyte.user.repository.SellerSaleRepository;

@Component
public class OrderSaleListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSaleListener.class);

    private final SellerSaleRepository sellerSaleRepository;
    private final ObjectMapper objectMapper;

    public OrderSaleListener(SellerSaleRepository sellerSaleRepository, ObjectMapper objectMapper) {
        this.sellerSaleRepository = sellerSaleRepository;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.ORDER_SALES_QUEUE)
    @Transactional
    public void onOrderSale(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        OrderSaleEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), OrderSaleEvent.class);
        } catch (IOException malformed) {
            throw reject(routingKey, malformed);
        }

        if (event == null || event.orderId() == null || event.orderId().isBlank() || event.orderId().length() > 36
                || event.sellerSub() == null || event.sellerSub().isBlank() || event.sellerSub().length() > 64
                || event.occurredAt() == null) {
            throw reject(routingKey, null);
        }

        if (!RebyteExchanges.ORDER_DELIVERED.equals(routingKey)
                && !RebyteExchanges.ORDER_REFUNDED.equals(routingKey)) {
            throw reject(routingKey, null);
        }

        if (RebyteExchanges.ORDER_DELIVERED.equals(routingKey)) {
            sellerSaleRepository.insertIfAbsent(event.orderId(), event.sellerSub());
            sellerSaleRepository.markDelivered(event.orderId(), event.occurredAt());
        } else if ("DELIVERED".equals(event.refundedFromStatus())) {
            sellerSaleRepository.insertIfAbsent(event.orderId(), event.sellerSub());
            sellerSaleRepository.markRefunded(event.orderId(), event.occurredAt());
        }
    }

    private AmqpRejectAndDontRequeueException reject(String routingKey, Throwable cause) {
        log.warn("Rejecting malformed order sale event routingKey={}", routingKey);
        return cause == null
                ? new AmqpRejectAndDontRequeueException("Invalid order sale event")
                : new AmqpRejectAndDontRequeueException("Invalid order sale event", cause);
    }
}
