package it.rebyte.messaging.realtime;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * D-30: a user is online while presence:{sub} holds at least one session scored in the future. Entries are
 * refreshed by the instance that owns the session, so a crashed instance's sessions age out after the TTL.
 * Transitions are pushed only to users who share a conversation with the subject.
 */
@Component
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);
    private static final String KEY_PREFIX = "presence:";
    private static final String CONVERSATIONS = "conversation";

    private final StringRedisTemplate redis;
    private final MongoTemplate mongo;
    private final RealtimePublisher publisher;
    private final Duration ttl;
    private final Map<String, String> localSessions = new ConcurrentHashMap<>();

    public PresenceService(StringRedisTemplate redis, MongoTemplate mongo, RealtimePublisher publisher,
            @Value("${rebyte.realtime.presence.ttl-ms}") long ttlMs) {
        this.redis = redis;
        this.mongo = mongo;
        this.publisher = publisher;
        this.ttl = Duration.ofMillis(ttlMs);
    }

    /**
     * Failures are contained here because Spring logs the whole CONNECT frame, bearer token included, when a
     * session event listener throws.
     */
    @EventListener
    public void connected(SessionConnectedEvent event) {
        try {
            onConnected(event);
        } catch (RuntimeException failure) {
            log.warn("Presence update on connect failed: {}", failure.getClass().getSimpleName());
        }
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        try {
            onDisconnected(event);
        } catch (RuntimeException failure) {
            log.warn("Presence update on disconnect failed: {}", failure.getClass().getSimpleName());
        }
    }

    private void onConnected(SessionConnectedEvent event) {
        Principal user = event.getUser();
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        if (user == null || sessionId == null) {
            return;
        }
        String sub = user.getName();
        localSessions.put(sessionId, sub);
        boolean wasOnline = isOnline(sub);
        score(sub, sessionId);
        if (!wasOnline) {
            pushToPartners(sub, true);
        }
    }

    private void onDisconnected(SessionDisconnectEvent event) {
        String sub = localSessions.remove(event.getSessionId());
        if (sub == null) {
            return;
        }
        redis.opsForZSet().remove(key(sub), event.getSessionId());
        if (!isOnline(sub)) {
            pushToPartners(sub, false);
        }
    }

    @Scheduled(fixedDelayString = "${rebyte.realtime.presence.refresh-ms}")
    public void refresh() {
        localSessions.forEach((sessionId, sub) -> score(sub, sessionId));
    }

    public boolean isOnline(String sub) {
        try {
            String key = key(sub);
            redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, System.currentTimeMillis());
            Long live = redis.opsForZSet().zCard(key);
            return live != null && live > 0;
        } catch (RuntimeException failure) {
            log.warn("Presence read failed: {}", failure.getClass().getSimpleName());
            return false;
        }
    }

    /** One pipelined round trip for a page of users. */
    public Map<String, Boolean> onlineMap(Collection<String> subs) {
        List<String> distinct = List.copyOf(new LinkedHashSet<>(subs));
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            double now = System.currentTimeMillis();
            List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (String sub : distinct) {
                    byte[] key = key(sub).getBytes(StandardCharsets.UTF_8);
                    connection.zSetCommands().zRemRangeByScore(key, Double.NEGATIVE_INFINITY, now);
                    connection.zSetCommands().zCard(key);
                }
                return null;
            });
            Map<String, Boolean> online = new HashMap<>();
            for (int i = 0; i < distinct.size(); i++) {
                Object card = results.get(i * 2 + 1);
                online.put(distinct.get(i), card instanceof Long count && count > 0);
            }
            return online;
        } catch (RuntimeException failure) {
            log.warn("Presence batch read failed: {}", failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    public Set<String> partners(String sub) {
        Set<String> partners = new LinkedHashSet<>();
        Query asBuyer = Query.query(Criteria.where("buyerSub").is(sub));
        asBuyer.fields().include("sellerSub");
        mongo.find(asBuyer, Document.class, CONVERSATIONS).forEach(row -> partners.add(row.getString("sellerSub")));
        Query asSeller = Query.query(Criteria.where("sellerSub").is(sub));
        asSeller.fields().include("buyerSub");
        mongo.find(asSeller, Document.class, CONVERSATIONS).forEach(row -> partners.add(row.getString("buyerSub")));
        return partners;
    }

    private void score(String sub, String sessionId) {
        String key = key(sub);
        redis.opsForZSet().add(key, sessionId, System.currentTimeMillis() + ttl.toMillis());
        redis.expire(key, ttl);
    }

    private void pushToPartners(String sub, boolean online) {
        Map<String, Object> payload = Map.of("userSub", sub, "online", online);
        for (String partner : partners(sub)) {
            publisher.push(partner, RealtimePublisher.PRESENCE, payload);
        }
    }

    private static String key(String sub) {
        return KEY_PREFIX + sub;
    }
}
