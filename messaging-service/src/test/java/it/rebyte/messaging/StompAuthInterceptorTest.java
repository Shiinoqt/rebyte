package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import it.rebyte.messaging.realtime.SessionRegistry;
import it.rebyte.messaging.realtime.StompAuthInterceptor;

class StompAuthInterceptorTest {

    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final JwtAuthenticationConverter converter = mock(JwtAuthenticationConverter.class);
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private StompAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthInterceptor(decoder, converter, sessions);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Bearer ", "Bearer    ", "Basic alice", "bearer alice"})
    void connectRequiresANonBlankBearerToken(String authorization) {
        StompHeaderAccessor accessor = headers(StompCommand.CONNECT, null, false);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(decoder, converter, sessions);
    }

    @Test
    void decoderFailureDoesNotExposeTokenOrDecoderDetails() {
        StompHeaderAccessor accessor = headers(StompCommand.CONNECT, null, false);
        accessor.setNativeHeader("Authorization", "Bearer secret-token");
        when(decoder.decode("secret-token")).thenThrow(new JwtException("rejected secret-token"));

        assertThatThrownBy(() -> interceptor.preSend(message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageNotContaining("secret-token")
                .hasNoCause()
                .satisfies(failure -> assertThat(((MessageDeliveryException) failure).getFailedMessage()).isNull());
        verifyNoInteractions(converter, sessions);
    }

    @Test
    void connectBindsTheConvertedSubPrincipalAndRegistersExpiry() {
        Instant expiry = Instant.now().plusSeconds(60);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("alice")
                .expiresAt(expiry).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt, List.of());
        when(decoder.decode("token")).thenReturn(jwt);
        when(converter.convert(jwt)).thenReturn(authentication);
        StompHeaderAccessor accessor = headers(StompCommand.CONNECT, null, false);
        accessor.setNativeHeader("Authorization", "Bearer token");
        Message<?> message = message(accessor);

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        assertThat(accessor.getUser()).isSameAs(authentication);
        assertThat(accessor.getUser().getName()).isEqualTo("alice");
        verify(sessions).expiresAt("session-1", expiry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/queue/messages", "/user/queue/notifications", "/user/queue/unread",
            "/user/queue/presence", "/user/queue/typing"})
    void authenticatedUsersCanSubscribeToTheirOwnQueues(String destination) {
        Message<?> message = message(headers(StompCommand.SUBSCRIBE, destination, true));
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/topic/anything", "/queue/messages", "/user/queue/other",
            "/user/bob/queue/messages", "/user/queue/messages/**"})
    void subscriptionsOutsideTheExactUserQueueAllowListAreRefused(String destination) {
        assertThatThrownBy(() -> interceptor.preSend(
                message(headers(StompCommand.SUBSCRIBE, destination, true)), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBSCRIBE", "SEND", "UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT"})
    void clientFramesBeforeAuthenticatedConnectAreRefused(String command) {
        String destination = command.equals("SEND") ? "/app/conversations/c1/typing" : "/user/queue/messages";
        StompHeaderAccessor accessor = headers(StompCommand.valueOf(command), destination, false);
        accessor.setNativeHeader("Authorization", "Bearer secret-token");
        assertThatThrownBy(() -> interceptor.preSend(
                message(accessor), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessage("STOMP authentication required")
                .hasNoCause()
                .satisfies(failure -> assertThat(((MessageDeliveryException) failure).getFailedMessage()).isNull());
        verifyNoInteractions(decoder, converter, sessions);
    }

    @Test
    void authenticatedSendToConversationTypingIsAllowed() {
        Message<?> message = message(headers(StompCommand.SEND, "/app/conversations/c1/typing", true));
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/app/anything-else", "/app/conversations//typing", "/app/conversations/c1/messages",
            "/app/conversations/c1/typing/extra", "/app/conversations/c1/c2/typing"})
    void sendOutsideConversationTypingIsRefused(String destination) {
        assertThatThrownBy(() -> interceptor.preSend(
                message(headers(StompCommand.SEND, destination, true)), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSUBSCRIBE", "ACK", "NACK", "BEGIN", "COMMIT", "ABORT"})
    void authenticatedClientControlFramesPass(String command) {
        Message<?> message = message(headers(StompCommand.valueOf(command), null, true));
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    @Test
    void disconnectBeforeConnectIsRefused() {
        Message<?> message = message(headers(StompCommand.DISCONNECT, null, false));
        assertThatThrownBy(() -> interceptor.preSend(message, null)).isInstanceOf(MessageDeliveryException.class);
        verifyNoInteractions(decoder, converter, sessions);
    }

    @Test
    void heartbeatPasses() {
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        Message<?> message = message(accessor);
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    private static StompHeaderAccessor headers(StompCommand command, String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        accessor.setDestination(destination);
        if (authenticated) {
            accessor.setUser(() -> "alice");
        }
        return accessor;
    }

    private static Message<?> message(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
