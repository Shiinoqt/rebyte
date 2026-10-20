package it.rebyte.messaging.support;

import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.WebSocketStompClient;

public final class StompTestClient implements AutoCloseable {

    private final Map<String, BlockingQueue<Frame>> frames = new ConcurrentHashMap<>();
    private final BlockingQueue<Frame> errors = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private final CompletableFuture<Throwable> failure = new CompletableFuture<>();
    private final WebSocketStompClient client;
    private volatile StompSession session;
    private volatile WebSocketSession socket;

    public StompTestClient() {
        StandardWebSocketClient transport = new StandardWebSocketClient() {
            @Override
            protected CompletableFuture<WebSocketSession> executeInternal(WebSocketHandler handler,
                    HttpHeaders headers, URI uri, List<String> protocols,
                    List<WebSocketExtension> extensions, Map<String, Object> attributes) {
                return super.executeInternal(new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession connected) throws Exception {
                        socket = connected;
                        super.afterConnectionEstablished(connected);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) throws Exception {
                        closed.complete(status);
                        super.afterConnectionClosed(socket, status);
                    }
                }, headers, uri, protocols, extensions, attributes);
            }
        };
        client = new WebSocketStompClient(transport);
        client.setMessageConverter(new SimpleMessageConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
    }

    public CompletableFuture<StompSession> connect(String url, StompHeaders connectHeaders) {
        return client.connectAsync(url, new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                errors.offer(new Frame(headers, (byte[]) payload));
            }

            @Override
            public void handleTransportError(StompSession connected, Throwable exception) {
                failure.complete(exception);
            }

            @Override
            public void handleException(StompSession connected,
                    org.springframework.messaging.simp.stomp.StompCommand command,
                    StompHeaders headers, byte[] payload, Throwable exception) {
                failure.complete(exception);
            }
        }).thenApply(connected -> {
            session = connected;
            return connected;
        });
    }

    public void subscribe(String destination) {
        BlockingQueue<Frame> queue = frames.computeIfAbsent(destination, key -> new LinkedBlockingQueue<>());
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.offer(new Frame(headers, (byte[]) payload));
            }
        });
    }

    public void send(String destination, byte[] payload) {
        session.send(destination, payload);
    }

    public Frame poll(String destination, Duration timeout) throws InterruptedException {
        return frames.computeIfAbsent(destination, key -> new LinkedBlockingQueue<>())
                .poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Frame error(Duration timeout) throws InterruptedException {
        return errors.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<CloseStatus> closed() {
        return closed;
    }

    public CompletableFuture<Throwable> failure() {
        return failure;
    }

    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    @Override
    public void close() {
        try {
            if (isConnected()) {
                try {
                    session.disconnect();
                } catch (IllegalStateException closedByServer) {
                    // The server may close a refused session between the isConnected check and the DISCONNECT.
                }
            }
            if (socket != null && socket.isOpen()) {
                socket.close();
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not close test socket", exception);
        } finally {
            client.stop();
        }
    }

    public record Frame(StompHeaders headers, byte[] body) {
    }
}
