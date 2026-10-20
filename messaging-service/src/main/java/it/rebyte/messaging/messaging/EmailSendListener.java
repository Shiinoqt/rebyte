package it.rebyte.messaging.messaging;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailSendService;

@Component
public class EmailSendListener {
    private final ObjectMapper mapper;
    private final EmailSendService service;

    public EmailSendListener(ObjectMapper mapper, EmailSendService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @RabbitListener(id = "emailSend", queues = RabbitConfig.EMAIL_SEND_QUEUE, concurrency = "1")
    public void onEmail(Message message) {
        EmailJob job;
        try {
            JsonNode payload = mapper.readTree(message.getBody());
            if (payload == null || !payload.isObject() || !text(payload, "kind")
                    || !text(payload, "recipientSub") || !text(payload, "dedupKey")
                    || !payload.path("data").isObject()) {
                throw new AmqpRejectAndDontRequeueException("Email job requires kind, recipientSub, dedupKey and data");
            }
            job = mapper.treeToValue(payload, EmailJob.class);
        } catch (IOException malformed) {
            throw new AmqpRejectAndDontRequeueException("Malformed email job");
        }
        if (job == null || job.kind() == null || missing(job.recipientSub()) || missing(job.dedupKey())
                || job.data() == null || missing(job.data().get(switch (job.kind()) {
                    case NEW_MESSAGE -> "conversationId";
                    case ORDER_PAID -> "orderId";
                    case NEW_VERSION -> "listingId";
                }))) {
            throw new AmqpRejectAndDontRequeueException("Email job requires kind, recipientSub, dedupKey and data");
        }
        service.process(job);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static boolean text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isTextual() && !value.textValue().isBlank();
    }
}
