package it.rebyte.messaging.realtime;

import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final Set<String> USER_QUEUES = Set.of("/user/queue/messages", "/user/queue/notifications",
            "/user/queue/unread", "/user/queue/presence", "/user/queue/typing");
    private static final Pattern TYPING = Pattern.compile("/app/conversations/[^/]+/typing");

    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;
    private final SessionRegistry sessions;

    public StompAuthInterceptor(JwtDecoder decoder, JwtAuthenticationConverter converter, SessionRegistry sessions) {
        this.decoder = decoder;
        this.converter = converter;
        this.sessions = sessions;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (command != null) {
            // StompSubProtocolHandler already rejects frames on an unconnected session; this keeps the rule
            // independent of it: without a user only CONNECT and heartbeats (no command) get through.
            if (accessor.getUser() == null) {
                throw new MessageDeliveryException("STOMP authentication required");
            }
            if (command == StompCommand.SUBSCRIBE || command == StompCommand.SEND) {
                String destination = accessor.getDestination();
                boolean allowed = destination != null && (command == StompCommand.SUBSCRIBE
                        ? USER_QUEUES.contains(destination) : TYPING.matcher(destination).matches());
                if (!allowed) {
                    throw new MessageDeliveryException("STOMP destination refused");
                }
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            throw new MessageDeliveryException("STOMP authentication failed");
        }
        try {
            Jwt jwt = decoder.decode(authorization.substring(7));
            var authentication = converter.convert(jwt);
            if (authentication == null || !authentication.isAuthenticated() || jwt.getExpiresAt() == null
                    || !jwt.getExpiresAt().isAfter(Instant.now()) || accessor.getSessionId() == null) {
                throw new MessageDeliveryException("STOMP authentication failed");
            }
            accessor.setUser(authentication);
            sessions.expiresAt(accessor.getSessionId(), jwt.getExpiresAt());
        } catch (RuntimeException failure) {
            // Decoder exceptions and failed messages can include the credential, so retain neither.
            throw new MessageDeliveryException("STOMP authentication failed");
        }
    }
}
