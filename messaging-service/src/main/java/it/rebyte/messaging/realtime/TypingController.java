package it.rebyte.messaging.realtime;

import java.security.Principal;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.repository.ConversationRepository;

/** D-30: typing is relayed to the other participant only, throttled per (user, conversation), never persisted. */
@Controller
public class TypingController {

    private final ConversationRepository conversations;
    private final StringRedisTemplate redis;
    private final RealtimePublisher publisher;
    private final long ttlMs;
    private final Duration minInterval;

    public TypingController(ConversationRepository conversations, StringRedisTemplate redis,
            RealtimePublisher publisher, @Value("${rebyte.realtime.typing.ttl-ms}") long ttlMs,
            @Value("${rebyte.realtime.typing.min-interval-ms}") long minIntervalMs) {
        this.conversations = conversations;
        this.redis = redis;
        this.publisher = publisher;
        this.ttlMs = ttlMs;
        this.minInterval = Duration.ofMillis(minIntervalMs);
    }

    @MessageMapping("/conversations/{conversationId}/typing")
    public void typing(@DestinationVariable String conversationId, Principal principal) {
        if (principal == null) {
            return;
        }
        String sub = principal.getName();
        Boolean allowed = redis.opsForValue().setIfAbsent("typing:user:" + sub, "1", minInterval);
        if (!Boolean.TRUE.equals(allowed)) {
            return;
        }
        Conversation conversation = conversations.findById(conversationId).orElse(null);
        if (conversation == null || !conversation.isParticipant(sub)) {
            return;
        }
        Boolean first = redis.opsForValue().setIfAbsent("typing:" + sub + ":" + conversationId, "1", minInterval);
        if (!Boolean.TRUE.equals(first)) {
            return;
        }
        publisher.push(conversation.otherParticipant(sub), RealtimePublisher.TYPING,
                Map.of("conversationId", conversationId, "userSub", sub, "ttlMs", ttlMs));
    }
}
