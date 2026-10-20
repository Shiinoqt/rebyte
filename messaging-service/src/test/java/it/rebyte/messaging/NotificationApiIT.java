package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.domain.OrderNotificationEvent;
import it.rebyte.messaging.dto.NotificationPage;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.dto.ReadAllResult;
import it.rebyte.messaging.dto.UnreadSummary;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.service.NotificationFactory;
import it.rebyte.messaging.service.NotificationWriter;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class NotificationApiIT extends MessagingTestBase {

    @Autowired TestRestTemplate rest;
    @Autowired MongoTemplate mongo;
    @Autowired NotificationWriter writer;
    @Autowired ObjectMapper objectMapper;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired RealtimePublisher publisher;

    @Value("${rebyte.realtime.push-channel}")
    String channel;

    private final LinkedBlockingQueue<String> pushes = new LinkedBlockingQueue<>();
    private RedisMessageListenerContainer listener;

    private List<Notification> alice;
    private List<Notification> bob;

    @BeforeEach
    void seedNotifications() {
        mongo.remove(new Query(), Notification.class);
        alice = seed("alice", 45, 10);
        bob = seed("bob", 3, 1);
    }

    @AfterEach
    void stopListener() throws Exception {
        if (listener != null) {
            listener.stop();
            listener.destroy();
        }
    }

    @Test
    void anonymousRequestsAreRejected() {
        for (String path : List.of("/notifications", "/notifications/unread-summary")) {
            assertThat(rest.getForEntity(path, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        for (String path : List.of("/notifications/" + alice.get(10).getId() + "/read", "/notifications/read-all")) {
            assertThat(request(null, path, HttpMethod.POST, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        for (String path : List.of("/notifications/" + alice.getFirst().getId(), "/notifications?read=true")) {
            assertThat(request(null, path, HttpMethod.DELETE, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void defaultPagesAreNewestFirstWithoutMissingOrRepeatedIds() {
        List<NotificationView> items = pages("alice", "", 20, 20, 5);

        assertThat(items).extracting(NotificationView::id).containsExactlyElementsOf(sortedIds(alice));
        assertThat(items).extracting(NotificationView::id).doesNotHaveDuplicates();
        assertThat(items).extracting(NotificationView::type).containsAll(List.of(NotificationType.values()));
        assertThat(items).allSatisfy(item -> assertThat(item.createdAt().getNano() % 1_000_000).isZero());
        assertThat(items.stream().filter(item -> item.createdAt().equals(alice.get(25).getCreatedAt())))
                .hasSize(3);
    }

    @Test
    void sizeOnePagesAlsoPreserveEveryEqualMillisecondId() {
        int[] sizes = new int[45];
        java.util.Arrays.fill(sizes, 1);

        assertThat(pages("alice", "size=1", sizes)).extracting(NotificationView::id)
                .containsExactlyElementsOf(sortedIds(alice));
    }

    @Test
    void unreadFilterPaginatesOnlyTheCallersUnreadNotifications() {
        List<NotificationView> items = pages("alice", "unread=true", 20, 15);

        assertThat(items).allSatisfy(item -> assertThat(item.read()).isFalse());
        assertThat(items).extracting(NotificationView::id)
                .containsExactlyElementsOf(sortedIds(alice.stream().filter(row -> !row.isRead()).toList()));
        assertThat(get("alice", "/notifications?unread=false&size=50", NotificationPage.class).getBody().items())
                .hasSize(45);
    }

    @Test
    void sizeFiftyReturnsAllRowsAndNoContinuation() {
        assertThat(pages("alice", "size=50", 45)).hasSize(45);
    }

    @Test
    void invalidPageParametersAreBadRequests() throws Exception {
        for (String query : List.of("size=0", "size=51", "cursor=garbage!", "cursor=aW52YWxpZHxpZA")) {
            assertProblem(get("alice", "/notifications?" + query, String.class),
                    HttpStatus.BAD_REQUEST, "invalid-cursor");
        }
        assertThat(get("alice", "/notifications?size=abc", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bobSeesOnlyHisNotificationsAndSummary() {
        assertThat(pages("bob", "", 3)).extracting(NotificationView::id)
                .containsExactlyElementsOf(sortedIds(bob));
        assertThat(get("alice", "/notifications/unread-summary", UnreadSummary.class).getBody())
                .isEqualTo(new UnreadSummary(35, 0));
        assertThat(get("bob", "/notifications/unread-summary", UnreadSummary.class).getBody())
                .isEqualTo(new UnreadSummary(2, 0));
    }

    @Test
    void userWithoutNotificationsGetsAnEmptyPageAndZeroTotals() {
        assertThat(pages("carol", "", 0)).isEmpty();
        assertThat(get("carol", "/notifications/unread-summary", UnreadSummary.class).getBody())
                .isEqualTo(new UnreadSummary(0, 0));
    }

    @Test
    void readAndReadAllSetRetentionPreserveFirstReadTimesAndPushUnreadSummaries() throws Exception {
        startListener();
        String id = alice.get(10).getId();
        Instant initialReadAt = alice.getFirst().getReadAt();

        ResponseEntity<NotificationView> read = request("alice", "/notifications/" + id + "/read",
                HttpMethod.POST, NotificationView.class);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().read()).isTrue();
        Instant firstReadAt = read.getBody().readAt();
        assertThat(firstReadAt).isNotNull();
        assertThat(firstReadAt.getNano() % 1_000_000).isZero();
        assertThat(stored(id).getExpiresAt()).isEqualTo(firstReadAt.plusSeconds(2_592_000));
        assertUnreadPush("alice", 34, 0);

        assertThat(request("alice", "/notifications/" + id + "/read", HttpMethod.POST, NotificationView.class)
                .getBody().readAt()).isEqualTo(firstReadAt);
        assertThat(stored(id).getExpiresAt()).isEqualTo(firstReadAt.plusSeconds(2_592_000));

        ResponseEntity<ReadAllResult> all = request("alice", "/notifications/read-all", HttpMethod.POST, ReadAllResult.class);

        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(all.getBody()).isEqualTo(new ReadAllResult(34));
        assertUnreadPush("alice", 0, 0);
        assertThat(get("alice", "/notifications/unread-summary", UnreadSummary.class).getBody())
                .isEqualTo(new UnreadSummary(0, 0));
        for (Notification notification : alice) {
            Notification after = stored(notification.getId());
            assertThat(after.isRead()).isTrue();
            assertThat(after.getReadAt()).isNotNull();
            assertThat(after.getExpiresAt()).isEqualTo(after.getReadAt().plusSeconds(2_592_000));
        }
        assertThat(stored(alice.getFirst().getId()).getReadAt()).isEqualTo(initialReadAt);
        assertThat(stored(id).getReadAt()).isEqualTo(firstReadAt);
        assertThat(bob.stream().map(row -> stored(row.getId()).isRead())).containsExactly(true, false, false);

        assertThat(request("alice", "/notifications/read-all", HttpMethod.POST, ReadAllResult.class).getBody())
                .isEqualTo(new ReadAllResult(0));
        assertUnreadPush("alice", 0, 0);
    }

    @Test
    void concurrentReadsKeepTheFirstCommittedReadTimestamp() throws Exception {
        String path = "/notifications/" + alice.get(10).getId() + "/read";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> simultaneousRead(path, ready, start));
        var second = CompletableFuture.supplyAsync(() -> simultaneousRead(path, ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        ResponseEntity<NotificationView> firstResponse = first.get(10, TimeUnit.SECONDS);
        ResponseEntity<NotificationView> secondResponse = second.get(10, TimeUnit.SECONDS);

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResponse.getBody().readAt()).isEqualTo(secondResponse.getBody().readAt());
        assertThat(stored(alice.get(10).getId()).getReadAt()).isEqualTo(firstResponse.getBody().readAt());
    }

    @Test
    void foreignAndMissingIdsReturnNotFoundWithoutChangingNotifications() throws Exception {
        for (String id : List.of(alice.get(10).getId(), alice.getFirst().getId(), "missing")) {
            assertProblem(request("bob", "/notifications/" + id + "/read", HttpMethod.POST, String.class),
                    HttpStatus.NOT_FOUND, "notification-not-found");
            assertProblem(request("bob", "/notifications/" + id, HttpMethod.DELETE, String.class),
                    HttpStatus.NOT_FOUND, "notification-not-found");
        }
        assertThat(stored(alice.get(10).getId()).isRead()).isFalse();
        assertThat(stored(alice.get(10).getId()).getReadAt()).isNull();
        assertThat(stored(alice.get(10).getId()).getExpiresAt()).isNull();
        assertThat(stored(alice.getFirst().getId()).isRead()).isTrue();
        assertThat(get("alice", "/notifications/unread-summary", UnreadSummary.class).getBody())
                .isEqualTo(new UnreadSummary(35, 0));
    }

    @Test
    void singleDeleteRejectsUnreadAndRemovesReadNotifications() throws Exception {
        Notification unread = NotificationFactory.follow("alice", "new-follower", Map.of());
        assertThat(writer.upsertIfAbsent(unread)).isTrue();
        assertProblem(request("alice", "/notifications/" + unread.getId(), HttpMethod.DELETE, String.class),
                HttpStatus.CONFLICT, "notification-unread");
        assertThat(stored(unread.getId()).isRead()).isFalse();
        assertThat(stored(unread.getId()).getExpiresAt()).isNull();

        String readId = alice.getFirst().getId();
        ResponseEntity<String> response = request("alice", "/notifications/" + readId, HttpMethod.DELETE, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(stored(readId)).isNull();
        assertProblem(request("alice", "/notifications/" + readId, HttpMethod.DELETE, String.class),
                HttpStatus.NOT_FOUND, "notification-not-found");
    }

    @Test
    void bulkDeleteRequiresReadTrueAndKeepsUnreadAndOtherUsersRows() throws Exception {
        for (String path : List.of("/notifications", "/notifications?read=false")) {
            assertProblem(request("alice", path, HttpMethod.DELETE, String.class),
                    HttpStatus.BAD_REQUEST, "invalid-delete-filter");
        }
        assertThat(pages("alice", "size=50", 45)).hasSize(45);

        ResponseEntity<String> response = request("alice", "/notifications?read=true", HttpMethod.DELETE, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(pages("alice", "size=50", 35)).allSatisfy(item -> assertThat(item.read()).isFalse());
        assertThat(pages("bob", "", 3)).extracting(NotificationView::id).containsExactlyElementsOf(sortedIds(bob));
        assertThat(stored(bob.getFirst().getId()).isRead()).isTrue();
        assertThat(request("alice", "/notifications?read=true", HttpMethod.DELETE, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void retentionIndexIsPresentAndUnreadNotificationsHaveNoExpiry() {
        var ttl = mongo.indexOps(Notification.class).getIndexInfo().stream()
                .filter(index -> index.getIndexFields().stream().map(IndexField::getKey).toList()
                        .equals(List.of("expiresAt")))
                .findFirst().orElseThrow(() -> new AssertionError("Missing expiresAt TTL index"));

        assertThat(ttl.getExpireAfter()).contains(Duration.ZERO);
        assertThat(alice.stream().filter(row -> !row.isRead())).allSatisfy(row -> {
            assertThat(row.getReadAt()).isNull();
            assertThat(row.getExpiresAt()).isNull();
        });
    }

    private ResponseEntity<NotificationView> simultaneousRead(String path, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Concurrent read did not start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        return request("alice", path, HttpMethod.POST, NotificationView.class);
    }

    private Notification stored(String id) {
        return mongo.findById(id, Notification.class);
    }

    private void startListener() throws Exception {
        listener = new RedisMessageListenerContainer();
        listener.setConnectionFactory(redisConnectionFactory);
        listener.addMessageListener((message, pattern) -> pushes.offer(new String(message.getBody(), StandardCharsets.UTF_8)),
                ChannelTopic.of(channel));
        listener.afterPropertiesSet();
        listener.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            publisher.push("probe", RealtimePublisher.PRESENCE, Map.of("ready", true));
            if (pushes.poll(100, TimeUnit.MILLISECONDS) != null) {
                return;
            }
        }
        throw new AssertionError("Redis subscriber did not receive the readiness probe");
    }

    private void assertUnreadPush(String sub, long notifications, long messages) throws Exception {
        String raw = pushes.poll(5, TimeUnit.SECONDS);
        assertThat(raw).as("unread summary push").isNotNull();
        JsonNode envelope = objectMapper.readTree(raw);
        assertThat(envelope.path("userSub").asText()).isEqualTo(sub);
        assertThat(envelope.path("destination").asText()).isEqualTo(RealtimePublisher.UNREAD);
        JsonNode payload = objectMapper.readTree(envelope.path("payload").asText());
        assertThat(payload.path("notifications").asLong()).isEqualTo(notifications);
        assertThat(payload.path("messages").asLong()).isEqualTo(messages);
    }

    private List<Notification> seed(String sub, int count, int readCount) {
        List<Notification> rows = new ArrayList<>();
        Instant base = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        Instant shared = base.plusSeconds(25).plusNanos(123_456_789);
        for (int i = 0; i < count; i++) {
            Notification notification = mixedNotification(sub, i);
            if (i < readCount) {
                notification.markRead(base, Duration.ofDays(30));
            }
            assertThat(writer.upsertIfAbsent(notification)).isTrue();
            Instant createdAt = i >= 24 && i <= 26 ? shared : base.plusSeconds(i);
            mongo.updateFirst(Query.query(Criteria.where("_id").is(notification.getId())),
                    new Update().set("createdAt", createdAt), Notification.class);
            rows.add(mongo.findById(notification.getId(), Notification.class));
        }
        return rows;
    }

    private Notification mixedNotification(String sub, int i) {
        String target = sub + "-" + i;
        Map<String, String> snapshot = Map.of("actorUsername", "Città", "listingTitle", "Hardware " + i);
        return switch (i % 9) {
            case 0 -> NotificationFactory.message(sub, target, "conversation-" + target, i + 1, snapshot);
            case 1 -> NotificationFactory.favourite(sub, target, "listing-" + target, snapshot);
            case 2 -> NotificationFactory.priceDrop(sub, target, Instant.now(), snapshot);
            case 3 -> NotificationFactory.release(sub, target, "1", snapshot);
            case 4 -> NotificationFactory.review(sub, target, snapshot);
            case 5 -> NotificationFactory.follow(sub, target, snapshot);
            case 6 -> NotificationFactory.offerReceived(sub, target, snapshot);
            case 7 -> NotificationFactory.offerAccepted(sub, target, snapshot);
            default -> NotificationFactory.order(sub, target, OrderNotificationEvent.SHIPPED, target, snapshot);
        };
    }

    private List<NotificationView> pages(String sub, String parameters, int... expectedSizes) {
        List<NotificationView> items = new ArrayList<>();
        String cursor = null;
        for (int i = 0; i < expectedSizes.length; i++) {
            String path = "/notifications?" + parameters + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<NotificationPage> response = get(sub, path, NotificationPage.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            NotificationPage page = response.getBody();
            assertThat(page).isNotNull();
            assertThat(page.items()).hasSize(expectedSizes[i]);
            items.addAll(page.items());
            cursor = page.nextCursor();
            if (i == expectedSizes.length - 1) {
                assertThat(cursor).isNull();
            } else {
                assertThat(cursor).isNotBlank();
            }
        }
        return items;
    }

    private static List<String> sortedIds(List<Notification> rows) {
        return rows.stream().sorted(Comparator.comparing(Notification::getCreatedAt)
                .thenComparing(Notification::getId).reversed()).map(Notification::getId).toList();
    }

    private <T> ResponseEntity<T> get(String sub, String path, Class<T> bodyType) {
        return request(sub, path, HttpMethod.GET, bodyType);
    }

    private <T> ResponseEntity<T> request(String sub, String path, HttpMethod method, Class<T> bodyType) {
        HttpHeaders headers = new HttpHeaders();
        if (sub != null) {
            headers.setBearerAuth(TestJwtSupport.mint(sub));
        }
        return rest.exchange(path, method, new HttpEntity<>(headers), bodyType);
    }

    private void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(objectMapper.readTree(response.getBody()).path("type").asText())
                .isEqualTo("urn:rebyte:problem:" + code);
    }
}
