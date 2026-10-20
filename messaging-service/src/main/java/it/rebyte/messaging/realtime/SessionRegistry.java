package it.rebyte.messaging.realtime;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

@Component
public class SessionRegistry {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long connectTimeoutMs;

    public SessionRegistry(@Value("${rebyte.realtime.time-to-first-message-ms}") long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
                sessions.put(socket.getId(), new Session(socket, Instant.now().plusMillis(connectTimeoutMs)));
                try {
                    super.afterConnectionEstablished(socket);
                } catch (Exception failure) {
                    sessions.remove(socket.getId());
                    throw failure;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) throws Exception {
                sessions.remove(socket.getId());
                super.afterConnectionClosed(socket, status);
            }
        };
    }

    public void expiresAt(String sessionId, Instant expiry) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.expiry = expiry;
        }
    }

    @Scheduled(fixedDelayString = "${rebyte.realtime.expiry-sweep-ms}")
    public void closeExpired() {
        Instant now = Instant.now();
        for (Session session : sessions.values()) {
            Instant expiry = session.expiry;
            CloseStatus status = null;
            if (expiry != null && !expiry.isAfter(now)) {
                status = CloseStatus.POLICY_VIOLATION.withReason("token expired");
            } else if (expiry == null && !session.connectDeadline.isAfter(now)) {
                // Spring checks idle transports on connection activity; a silent socket needs its own sweep.
                status = CloseStatus.POLICY_VIOLATION.withReason("CONNECT timeout");
            }
            if (status != null && session.socket.isOpen()) {
                try {
                    session.socket.close(status);
                } catch (IOException failure) {
                    // Keep the entry so the next sweep retries the close without logging a socket URL.
                }
            }
        }
    }

    private static final class Session {
        private final WebSocketSession socket;
        private final Instant connectDeadline;
        private volatile Instant expiry;

        private Session(WebSocketSession socket, Instant connectDeadline) {
            this.socket = socket;
            this.connectDeadline = connectDeadline;
        }
    }
}
