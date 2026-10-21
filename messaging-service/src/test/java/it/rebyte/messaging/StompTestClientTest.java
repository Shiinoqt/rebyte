package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import it.rebyte.messaging.support.StompTestClient;

class StompTestClientTest {
    @Test
    void closeNotificationWaitsForStompSessionCleanup() throws Exception {
        WebSocketContainer container = mock(WebSocketContainer.class);
        Session nativeSession = mock(Session.class);
        AtomicReference<Endpoint> endpoint = new AtomicReference<>();
        when(container.connectToServer(any(Endpoint.class), any(ClientEndpointConfig.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    endpoint.set(invocation.getArgument(0));
                    return nativeSession;
                });
        AtomicBoolean connected = new AtomicBoolean(true);
        StompSession session = mock(StompSession.class);
        when(session.isConnected()).thenAnswer(invocation -> connected.get());
        WebSocketHandler handler = mock(WebSocketHandler.class);
        doAnswer(invocation -> {
            connected.set(false);
            return null;
        }).when(handler).afterConnectionClosed(any(WebSocketSession.class), any(CloseStatus.class));

        try (StompTestClient client = new StompTestClient()) {
            ReflectionTestUtils.setField(client, "session", session);
            WebSocketStompClient stomp = (WebSocketStompClient) ReflectionTestUtils.getField(client, "client");
            var transport = stomp.getWebSocketClient();
            ReflectionTestUtils.setField(transport, "webSocketContainer", container);
            transport.execute(handler, new WebSocketHttpHeaders(), URI.create("ws://localhost/callback-order"))
                    .get(5, TimeUnit.SECONDS);
            var connectedWhenNotified = client.closed().thenApply(status -> client.isConnected());

            endpoint.get().onClose(nativeSession, new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, ""));

            assertThat(connectedWhenNotified).isCompletedWithValue(false);
        }
    }

    @Test
    void cleanupClosesTransportAfterWrappedServerClosure() throws Exception {
        StompSession session = mock(StompSession.class);
        WebSocketSession socket = mock(WebSocketSession.class);
        StompTestClient client = new StompTestClient();
        ReflectionTestUtils.setField(client, "session", session);
        ReflectionTestUtils.setField(client, "socket", socket);
        when(session.isConnected()).thenReturn(true);
        when(socket.isOpen()).thenReturn(true);
        doThrow(new MessageDeliveryException(new GenericMessage<>(new byte[0]),
                new IllegalStateException("WebSocket session has been closed")))
                .when(session).disconnect();

        assertThatCode(client::close).doesNotThrowAnyException();

        verify(socket).close();
    }

    @Test
    void cleanupPreservesOtherDeliveryFailures() {
        StompSession session = mock(StompSession.class);
        StompTestClient client = new StompTestClient();
        ReflectionTestUtils.setField(client, "session", session);
        when(session.isConnected()).thenReturn(true);
        MessageDeliveryException failure = new MessageDeliveryException(new GenericMessage<>(new byte[0]),
                new IOException("Transport write failed"));
        doThrow(failure).when(session).disconnect();

        assertThatThrownBy(client::close).isSameAs(failure);
    }
}
