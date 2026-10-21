package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import it.rebyte.messaging.support.StompTestClient;

class StompTestClientTest {
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
