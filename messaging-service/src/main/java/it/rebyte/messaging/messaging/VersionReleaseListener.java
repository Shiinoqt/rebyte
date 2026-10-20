package it.rebyte.messaging.messaging;

import java.io.IOException;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.service.ReleaseFanOutService;

@Component
public class VersionReleaseListener {

    private final ObjectMapper mapper;
    private final ReleaseFanOutService notifications;

    public VersionReleaseListener(ObjectMapper mapper, ReleaseFanOutService notifications) {
        this.mapper = mapper;
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitConfig.VERSION_RELEASED_QUEUE)
    public void onEvent(Message message) {
        VersionReleasedEvent event;
        try {
            event = mapper.readValue(message.getBody(), VersionReleasedEvent.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Invalid version release event", malformed);
        }
        if (event == null || blank(event.listingId()) || blank(event.sellerSub()) || blank(event.versionNumber())) {
            throw new AmqpRejectAndDontRequeueException("Invalid version release event");
        }
        notifications.fanOut(event);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
