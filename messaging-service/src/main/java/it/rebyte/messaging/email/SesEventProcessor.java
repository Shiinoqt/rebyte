package it.rebyte.messaging.email;

import java.time.Instant;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.domain.EmailSuppression;

/**
 * Turns one raw SES event (the SQS body, SNS raw delivery) into suppression
 * decisions. Only permanent bounces and complaints suppress an address; a
 * transient bounce or a reject says nothing durable about the address.
 *
 * <p>Failures carry no part of the body in their message and the logs carry
 * only event types and message ids, because the body holds email addresses.
 */
@Component
public class SesEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(SesEventProcessor.class);

    private final ObjectMapper objectMapper;
    private final SuppressionService suppressions;

    public SesEventProcessor(ObjectMapper objectMapper, SuppressionService suppressions) {
        this.objectMapper = objectMapper;
        this.suppressions = suppressions;
    }

    /** Throws IllegalArgumentException for a body that is not a SES event, so the poller leaves it for redrive. */
    public void process(String body) {
        JsonNode event = parse(body);
        String eventType = event.path("eventType").asText("");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("SES event has no eventType");
        }
        String sesMessageId = event.path("mail").path("messageId").asText(null);

        switch (eventType) {
            case "Bounce" -> processBounce(event.path("bounce"), sesMessageId);
            case "Complaint" -> processComplaint(event.path("complaint"), sesMessageId);
            case "Reject" -> log.info("SES reject eventType={} sesMessageId={}", eventType, sesMessageId);
            default -> log.info("SES event ignored eventType={} sesMessageId={}", eventType, sesMessageId);
        }
    }

    private void processBounce(JsonNode bounce, String sesMessageId) {
        String bounceType = bounce.path("bounceType").asText("");
        if (!"Permanent".equals(bounceType)) {
            log.info("SES bounce not suppressed bounceType={} sesMessageId={}", bounceType, sesMessageId);
            return;
        }
        int recorded = recordAll(bounce.path("bouncedRecipients"), EmailSuppression.BOUNCE_PERMANENT,
                bounce.path("bounceSubType").asText(null), sesMessageId);
        log.info("SES permanent bounce suppressed={} sesMessageId={}", recorded, sesMessageId);
    }

    private void processComplaint(JsonNode complaint, String sesMessageId) {
        int recorded = recordAll(complaint.path("complainedRecipients"), EmailSuppression.COMPLAINT,
                complaint.path("complaintFeedbackType").asText(null), sesMessageId);
        log.info("SES complaint suppressed={} sesMessageId={}", recorded, sesMessageId);
    }

    private int recordAll(JsonNode recipients, String reason, String subType, String sesMessageId) {
        int recorded = 0;
        Instant now = Instant.now();
        for (JsonNode recipient : recipients) {
            String address = recipient.path("emailAddress").asText("").trim().toLowerCase(Locale.ROOT);
            if (!address.isEmpty()) {
                suppressions.record(address, reason, subType, sesMessageId, now);
                recorded++;
            }
        }
        return recorded;
    }

    private JsonNode parse(String body) {
        JsonNode event;
        try {
            event = objectMapper.readTree(body);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalArgumentException("SES event is not valid JSON");
        }
        if (event == null || !event.isObject()) {
            throw new IllegalArgumentException("SES event is not a JSON object");
        }
        return event;
    }
}
