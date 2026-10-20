package it.rebyte.messaging.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RealtimePublisher {

    public static final String MESSAGES = "/queue/messages";
    public static final String NOTIFICATIONS = "/queue/notifications";
    public static final String UNREAD = "/queue/unread";
    public static final String PRESENCE = "/queue/presence";
    public static final String TYPING = "/queue/typing";

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final String channel;

    public RealtimePublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
            @Value("${rebyte.realtime.push-channel}") String channel) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.channel = channel;
    }

    /** Each messaging instance forwards Redis pushes only to sessions it holds for the recipient. */
    public void push(String userSub, String destination, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String envelopeJson = objectMapper.writeValueAsString(new PushEnvelope(userSub, destination, payloadJson));
            redis.convertAndSend(channel, envelopeJson);
        } catch (JsonProcessingException | RuntimeException failure) {
            log.warn("Realtime push failed for {}: {}", destination, failure.getClass().getSimpleName());
        }
    }

    private record PushEnvelope(String userSub, String destination, String payload) {
    }
}
