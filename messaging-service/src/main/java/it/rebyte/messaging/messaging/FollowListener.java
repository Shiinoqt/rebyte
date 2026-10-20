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
import it.rebyte.messaging.service.FollowNotificationService;

@Component
public class FollowListener {

    private static final Logger log = LoggerFactory.getLogger(FollowListener.class);

    private final ObjectReader reader;
    private final FollowNotificationService notifications;

    public FollowListener(ObjectMapper mapper, FollowNotificationService notifications) {
        this.reader = mapper.readerFor(FollowEvent.class).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitConfig.FOLLOW_QUEUE)
    public void onEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        FollowEvent event;
        try {
            event = reader.readValue(message.getBody());
        } catch (IOException malformed) {
            throw reject(routingKey);
        }
        if (event == null || !validId(event.eventId()) || !validId(event.followerSub())
                || !validId(event.followedSub()) || event.followerSub().equals(event.followedSub())
                || event.occurredAt() == null
                || (event.relationshipSequence() != null && event.relationshipSequence() <= 0)) {
            throw reject(routingKey);
        }
        if (RebyteExchanges.USER_FOLLOWED.equals(routingKey)) {
            notifications.followed(event);
        } else if (RebyteExchanges.USER_UNFOLLOWED.equals(routingKey)) {
            notifications.unfollowed(event);
        } else {
            throw reject(routingKey);
        }
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 64
                && value.chars().allMatch(c -> c <= 0x7F);
    }

    private AmqpRejectAndDontRequeueException reject(String routingKey) {
        log.warn("Rejecting invalid follow event routingKey={}", routingKey);
        return new AmqpRejectAndDontRequeueException("Invalid follow event");
    }
}
