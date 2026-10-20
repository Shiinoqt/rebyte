package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import it.rebyte.messaging.client.CatalogServiceClient;
import it.rebyte.messaging.client.ListingAvailability;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.dto.StartConversationRequest;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.realtime.PresenceService;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.repository.ConversationRepository;
import it.rebyte.messaging.service.ChatImageService;
import it.rebyte.messaging.service.ConversationQueryService;
import it.rebyte.messaging.service.ConversationService;
import it.rebyte.messaging.service.ConversationViews;
import it.rebyte.messaging.service.ConversationWriter;
import it.rebyte.messaging.service.MessageViews;
import it.rebyte.messaging.service.MongoTx;
import it.rebyte.messaging.service.NotificationPushes;
import it.rebyte.messaging.service.ReadService;
import it.rebyte.messaging.service.UsernameLookup;

class ConversationPresenceTest {

    private static final String LISTING = "9d391d4b-04e9-4e1f-bc96-bd4a800942b8";
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final CatalogServiceClient catalog = mock(CatalogServiceClient.class);
    private final UsernameLookup usernames = mock(UsernameLookup.class);
    private final MongoTx tx = mock(MongoTx.class);
    private final ConversationWriter writer = mock(ConversationWriter.class);
    private final EmailJobs emailJobs = mock(EmailJobs.class);
    private final RealtimePublisher publisher = mock(RealtimePublisher.class);
    private final ChatImageService images = mock(ChatImageService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PresenceService presence = new PresenceService(redis, mongo, publisher, 30000);
    private final ConversationViews views = new ConversationViews();
    private final MessageViews messages = new MessageViews(images);
    private final ConversationService service = new ConversationService(conversations, catalog, usernames, tx,
            writer, emailJobs, views, messages, publisher, mock(NotificationPushes.class), images, presence);

    @Test
    void committedStartStillReturnsItsConversationWhenRedisIsUnavailable() {
        AtomicBoolean committed = new AtomicBoolean();
        when(tx.execute(any())).thenAnswer(invocation -> {
            Object result = invocation.<Supplier<?>>getArgument(0).get();
            committed.set(true);
            return result;
        });
        when(conversations.insert(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(catalog.availability(LISTING)).thenReturn(new ListingAvailability(LISTING, "seller", "Keyboard",
                BigDecimal.TEN, "HARDWARE", true));
        when(images.validateAttachments("buyer", null)).thenReturn(List.of());
        when(writer.append(anyString(), any(), any())).thenReturn(new ConversationWriter.Appended(
                new Message("message", "chat", 1, "buyer", "seller", MessageKind.CHAT, "Hello", null,
                        null, null, Instant.now(), false, null), null, conversation()));
        when(redis.opsForZSet()).thenAnswer(invocation -> {
            assertThat(committed).isTrue();
            throw new RedisConnectionFailureException("unavailable");
        });

        var result = service.start("buyer", new StartConversationRequest(LISTING, "Hello", null));

        assertThat(result.id()).isEqualTo("chat");
        assertThat(result.partnerOnline()).isFalse();
        verify(emailJobs).enqueue(any());
    }

    @Test
    void singleConversationReadReturnsOfflineWhenRedisIsUnavailable() {
        when(conversations.findById("chat")).thenReturn(Optional.of(conversation()));
        when(redis.opsForZSet()).thenThrow(new RedisConnectionFailureException("unavailable"));

        var result = service.get("buyer", "chat");

        assertThat(result.id()).isEqualTo("chat");
        assertThat(result.partnerOnline()).isFalse();
    }

    @Test
    void inboxStillReturnsConversationsWhenThePresencePipelineFails() {
        when(mongo.find(any(Query.class), eq(Conversation.class))).thenReturn(List.of(conversation()));
        when(usernames.usernames(List.of("seller"))).thenReturn(Map.of("seller", "Seller"));
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));
        var queries = new ConversationQueryService(mongo, conversations, usernames, views, messages,
                mock(ReadService.class), presence);

        var result = queries.list("buyer", null, 20);

        assertThat(result.items()).singleElement().satisfies(view -> {
            assertThat(view.id()).isEqualTo("chat");
            assertThat(view.partnerOnline()).isFalse();
        });
    }

    private static Conversation conversation() {
        return new Conversation("chat", LISTING, "HARDWARE", "buyer", "seller", "Keyboard", BigDecimal.TEN,
                Instant.now(), Instant.now(), null, 1, 0, 1, null, null, null, null, 0, 0);
    }
}
