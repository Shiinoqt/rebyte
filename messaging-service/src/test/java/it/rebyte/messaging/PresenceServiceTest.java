package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import it.rebyte.messaging.realtime.PresenceService;
import it.rebyte.messaging.realtime.RealtimePublisher;

class PresenceServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final PresenceService presence = new PresenceService(redis, mock(MongoTemplate.class),
            mock(RealtimePublisher.class), 30000);

    @Test
    void failedPresenceReadReportsOffline() {
        when(redis.opsForZSet()).thenThrow(new RedisConnectionFailureException("unavailable"));

        assertThat(presence.isOnline("partner")).isFalse();
    }

    @Test
    void failedPipelineReturnsAnEmptyMapForOfflineDefaults() {
        when(redis.executePipelined(any(RedisCallback.class)))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        assertThat(presence.onlineMap(List.of("alice", "bob"))).isEmpty();
    }

    @Test
    void failedReadsLogOnlyTheExceptionClass() {
        var logger = (Logger) LoggerFactory.getLogger(PresenceService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            when(redis.opsForZSet()).thenThrow(new RedisConnectionFailureException("private-connection-detail"));
            when(redis.executePipelined(any(RedisCallback.class)))
                    .thenThrow(new RedisConnectionFailureException("private-connection-detail"));

            assertThat(presence.isOnline("partner")).isFalse();
            assertThat(presence.onlineMap(List.of("partner"))).isEmpty();

            assertThat(events.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("RedisConnectionFailureException")
                        .doesNotContain("private-connection-detail", "partner");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void successfulReadsStillReportOnlineUsers() {
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zsets = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zsets);
        when(zsets.zCard("presence:alice")).thenReturn(1L);
        when(redis.executePipelined(any(RedisCallback.class))).thenReturn(List.of(0L, 1L, 0L, 0L));

        assertThat(presence.isOnline("alice")).isTrue();
        assertThat(presence.onlineMap(List.of("alice", "bob", "alice")))
                .containsEntry("alice", true).containsEntry("bob", false).hasSize(2);
    }
}
