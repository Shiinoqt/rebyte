package it.rebyte.messaging.realtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class RedisPushRelay {

    @Bean
    RedisMessageListenerContainer redisPushListener(RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper, SimpMessagingTemplate messaging,
            @Value("${rebyte.realtime.push-channel}") String channel) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                PushEnvelope envelope = objectMapper.readValue(message.getBody(), PushEnvelope.class);
                if (envelope.userSub() == null || envelope.destination() == null || envelope.payload() == null) {
                    return;
                }
                SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setLeaveMutable(true);
                // A prebuilt byte message preserves the JSON string produced by RealtimePublisher.
                messaging.send("/user/" + envelope.userSub().replace("/", "%2F") + envelope.destination(),
                        MessageBuilder.createMessage(envelope.payload().getBytes(StandardCharsets.UTF_8),
                                headers.getMessageHeaders()));
            } catch (IOException malformedEnvelope) {
                // Redis pub/sub has no retry lane; a malformed envelope cannot be delivered.
            }
        }, ChannelTopic.of(channel));
        return container;
    }

    private record PushEnvelope(String userSub, String destination, String payload) {
    }
}
