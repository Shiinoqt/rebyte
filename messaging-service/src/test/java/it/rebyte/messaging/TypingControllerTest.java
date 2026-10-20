package it.rebyte.messaging;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.realtime.TypingController;
import it.rebyte.messaging.repository.ConversationRepository;

class TypingControllerTest {

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RealtimePublisher publisher = mock(RealtimePublisher.class);
    private final TypingController controller = new TypingController(conversations, redis, publisher, 3000, 1000);

    @Test
    void throttledUserCannotTriggerReadsEvenByChangingConversationIds() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("typing:user:buyer", "1", Duration.ofSeconds(1))).thenReturn(false);

        controller.typing("first", () -> "buyer");
        controller.typing("second", () -> "buyer");

        verifyNoInteractions(conversations, publisher);
    }

    @Test
    void probingDifferentIdsConsumesTheSameUserThrottle() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("typing:user:buyer", "1", Duration.ofSeconds(1))).thenReturn(true, false, false);

        controller.typing("first", () -> "buyer");
        controller.typing("second", () -> "buyer");
        controller.typing("third", () -> "buyer");

        verify(conversations).findById("first");
        verify(conversations, never()).findById("second");
        verify(conversations, never()).findById("third");
        verifyNoInteractions(publisher);
    }

    @Test
    void allowedFrameStillRequiresParticipation() {
        allowUser("outsider");
        when(conversations.findById("chat")).thenReturn(Optional.of(conversation()));

        controller.typing("chat", () -> "outsider");

        verifyNoInteractions(publisher);
        verify(values, never()).setIfAbsent(eq("typing:outsider:chat"), anyString(), eq(Duration.ofSeconds(1)));
    }

    @Test
    void participantReceivesOnlyTheOtherParticipantsTypingPush() {
        allowUser("buyer");
        when(conversations.findById("chat")).thenReturn(Optional.of(conversation()));
        when(values.setIfAbsent("typing:buyer:chat", "1", Duration.ofSeconds(1))).thenReturn(true);

        controller.typing("chat", () -> "buyer");

        verify(publisher).push("seller", RealtimePublisher.TYPING,
                Map.of("conversationId", "chat", "userSub", "buyer", "ttlMs", 3000L));
    }

    @Test
    void anonymousFrameDoesNoWork() {
        controller.typing("chat", null);

        verifyNoInteractions(conversations, redis, publisher);
    }

    private void allowUser(String sub) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent("typing:user:" + sub, "1", Duration.ofSeconds(1))).thenReturn(true);
    }

    private static Conversation conversation() {
        return new Conversation("chat", "listing", "HARDWARE", "buyer", "seller", "Keyboard", BigDecimal.TEN,
                Instant.now(), null, null, 0, 0, 0, null, null, null, null, 0, 0);
    }
}
