package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.user.domain.Follow;
import it.rebyte.user.domain.FollowId;
import it.rebyte.user.messaging.UserFollowedEvent;
import it.rebyte.user.messaging.UserUnfollowedEvent;
import it.rebyte.user.outbox.DomainEventRecorder;
import it.rebyte.user.outbox.OutboxEvent;
import it.rebyte.user.outbox.OutboxRepository;
import it.rebyte.user.repository.FollowRepository;
import it.rebyte.user.service.FollowService;
import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

/**
 * The follow graph end to end, real HTTP (SOCL-01, SOCL-02, D-41, D-42):
 * idempotent follow/unfollow, exactly-once outbox writes on real state
 * change, self-follow refused at both the service and the database level,
 * and two concurrent first follows of the same pair leaving exactly one
 * row and one outbox event.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class FollowServiceIT extends MySqlTestBase {

    @DynamicPropertySource
    static void issuerMatchesReservedPort(DynamicPropertyRegistry registry) {
        int port = findFreePort();
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:" + port + "/");
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private FollowRepository followRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FollowService followService;

    @MockitoSpyBean
    private DomainEventRecorder domainEventRecorder;

    @AfterEach
    void cleanUp() {
        followRepository.deleteAll();
        outboxRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM follow_transition_sequence");
    }

    @Test
    void firstFollowCreatesOneRowAndOneOutboxRow() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        ResponseEntity<Void> response = follow(token, followedSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(followRepository.existsById(new FollowId(followerSub, followedSub))).isTrue();
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void repeatFollowChangesNothing() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        follow(token, followedSub);
        ResponseEntity<Void> second = follow(token, followedSub);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(followRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void unfollowRemovesRowAndWritesOneUnfollowOutboxRow() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        follow(token, followedSub);
        ResponseEntity<Void> response = unfollow(token, followedSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(followRepository.existsById(new FollowId(followerSub, followedSub))).isFalse();
        assertThat(outboxRepository.count()).isEqualTo(2); // one follow event, one unfollow event
    }

    @Test
    void repeatUnfollowWritesNothing() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        ResponseEntity<Void> response = unfollow(token, followedSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(followRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void selfFollowReturns422WithProblemBody() {
        String sub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, sub, List.of());

        ResponseEntity<String> response = restTemplate.exchange(
                "/users/" + sub + "/follow", HttpMethod.PUT, authenticated(token), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(followRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    /**
     * CR-01: a write that fails for a reason OTHER than the composite
     * primary key already existing (the genuine repeat case) or a lost
     * concurrency race must not be swallowed as a no-op. An oversized
     * {@code followedSub} violates the {@code VARCHAR(64)} column width in
     * {@code V4__follow.sql} — this is neither the repeat nor the race the
     * catch block in {@code FollowService.follow()} exists to absorb.
     */
    @Test
    void writeFailingForAnUnrelatedConstraintIsNotReportedAsSuccess() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = "a".repeat(65);
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        ResponseEntity<String> response = restTemplate.exchange(
                "/users/" + followedSub + "/follow", HttpMethod.PUT, authenticated(token), String.class);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getStatusCode().isError()).isTrue();
        assertThat(followRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void directSelfReferencingInsertIsRejectedByDatabase() {
        String sub = UUID.randomUUID().toString();
        Follow selfFollow = new Follow(new FollowId(sub, sub), Instant.now());

        assertThatThrownBy(() -> followRepository.saveAndFlush(selfFollow))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoConcurrentFirstFollowsLeaveOneRowAndOneOutboxRow() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        CompletableFuture<ResponseEntity<Void>> requestA =
                CompletableFuture.supplyAsync(() -> follow(token, followedSub));
        CompletableFuture<ResponseEntity<Void>> requestB =
                CompletableFuture.supplyAsync(() -> follow(token, followedSub));
        CompletableFuture.allOf(requestA, requestB).join();

        assertThat(requestA.join().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(requestB.join().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(followRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void everyRealTransitionPublishesAStrictlyIncreasingRetainedSequence() throws Exception {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        follow(token, followedSub);
        unfollow(token, followedSub);
        follow(token, followedSub);

        List<OutboxEvent> events = outboxRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        assertThat(events).hasSize(3);
        assertThat(events).extracting(OutboxEvent::getRoutingKey)
                .containsExactly("user.followed", "user.unfollowed", "user.followed");
        for (int i = 0; i < events.size(); i++) {
            JsonNode payload = objectMapper.readTree(events.get(i).getPayload());
            assertThat(payload.get("eventId").asText()).isEqualTo(events.get(i).getEventId());
            assertThat(payload.get("relationshipSequence").asLong()).isEqualTo(i + 1L);
            assertThat(payload.has("username")).isFalse();
            assertThat(payload.has("email")).isFalse();
        }
        assertThat(sequenceOf(followerSub, followedSub)).isEqualTo(3L);
    }

    @Test
    void repeatedFollowAndMissingUnfollowDoNotAdvanceTheSequence() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        unfollow(token, followedSub);
        follow(token, followedSub);
        follow(token, followedSub);

        assertThat(sequenceOf(followerSub, followedSub)).isEqualTo(1L);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void sequenceIsPerPair() {
        String followerSub = UUID.randomUUID().toString();
        String firstTarget = UUID.randomUUID().toString();
        String secondTarget = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        follow(token, firstTarget);
        unfollow(token, firstTarget);
        follow(token, secondTarget);

        assertThat(sequenceOf(followerSub, firstTarget)).isEqualTo(2L);
        assertThat(sequenceOf(followerSub, secondTarget)).isEqualTo(1L);
    }

    @Test
    void twoConcurrentUnfollowsPublishOneUnfollowEventWithTheNextSequence() throws Exception {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());
        follow(token, followedSub);

        CompletableFuture<ResponseEntity<Void>> requestA =
                CompletableFuture.supplyAsync(() -> unfollow(token, followedSub));
        CompletableFuture<ResponseEntity<Void>> requestB =
                CompletableFuture.supplyAsync(() -> unfollow(token, followedSub));
        CompletableFuture.allOf(requestA, requestB).join();

        assertThat(requestA.join().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(requestB.join().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(followRepository.count()).isZero();
        assertThat(sequenceOf(followerSub, followedSub)).isEqualTo(2L);
        List<OutboxEvent> unfollowed = outboxRepository.findAll().stream()
                .filter(event -> event.getRoutingKey().equals("user.unfollowed"))
                .toList();
        assertThat(unfollowed).hasSize(1);
        assertThat(objectMapper.readTree(unfollowed.get(0).getPayload()).get("relationshipSequence").asLong())
                .isEqualTo(2L);
    }

    @Test
    void failedFirstFollowLeavesNoFollowSequenceOrOutboxRow() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("recorder failure"))
                .when(domainEventRecorder).record(any(), any(), any(), any());

        assertThatThrownBy(() -> followService.follow(followerSub, followedSub))
                .isInstanceOf(IllegalStateException.class);

        assertThat(followRepository.existsById(new FollowId(followerSub, followedSub))).isFalse();
        assertThat(pairRowCount(followerSub, followedSub)).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void failedUnfollowKeepsFollowSequenceAndOutboxUnchanged() {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        followService.follow(followerSub, followedSub);
        doThrow(new IllegalStateException("recorder failure"))
                .when(domainEventRecorder).record(any(), any(), any(), any());

        assertThatThrownBy(() -> followService.unfollow(followerSub, followedSub))
                .isInstanceOf(IllegalStateException.class);

        assertThat(followRepository.existsById(new FollowId(followerSub, followedSub))).isTrue();
        assertThat(sequenceOf(followerSub, followedSub)).isEqualTo(1L);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void legacyPayloadsWithoutSequenceStillParse() throws Exception {
        String followed = "{\"eventId\":\"e1\",\"type\":\"user.followed\",\"followerSub\":\"a\","
                + "\"followedSub\":\"b\",\"occurredAt\":\"2026-01-01T00:00:00.123456Z\"}";
        String unfollowed = followed.replace("user.followed", "user.unfollowed");

        UserFollowedEvent followedEvent = objectMapper.readValue(followed, UserFollowedEvent.class);
        UserUnfollowedEvent unfollowedEvent = objectMapper.readValue(unfollowed, UserUnfollowedEvent.class);

        assertThat(followedEvent.relationshipSequence()).isNull();
        assertThat(followedEvent.occurredAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(unfollowedEvent.relationshipSequence()).isNull();
        assertThat(new UserFollowedEvent("e", "a", "b", Instant.EPOCH).relationshipSequence()).isNull();
        assertThat(new UserUnfollowedEvent("e", "a", "b", Instant.EPOCH).type()).isEqualTo("user.unfollowed");
    }

    @Test
    void publishedOccurredAtKeepsMicrosecondPrecision() throws Exception {
        String followerSub = UUID.randomUUID().toString();
        String followedSub = UUID.randomUUID().toString();
        String token = LocalAuthTokens.mint(restTemplate, followerSub, List.of());

        follow(token, followedSub);

        OutboxEvent event = outboxRepository.findAll().get(0);
        UserFollowedEvent payload = objectMapper.readValue(event.getPayload(), UserFollowedEvent.class);
        assertThat(payload.occurredAt()).isEqualTo(event.getOccurredAt());
    }

    private long pairRowCount(String followerSub, String followedSub) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follow_transition_sequence WHERE follower_sub = ? AND followed_sub = ?",
                Long.class, followerSub, followedSub);
        return value;
    }

    private long sequenceOf(String followerSub, String followedSub) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT transition_sequence FROM follow_transition_sequence WHERE follower_sub = ? AND followed_sub = ?",
                Long.class, followerSub, followedSub);
        return value;
    }

    private ResponseEntity<Void> follow(String token, String targetSub) {
        return restTemplate.exchange(
                "/users/" + targetSub + "/follow", HttpMethod.PUT, authenticated(token), Void.class);
    }

    private ResponseEntity<Void> unfollow(String token, String targetSub) {
        return restTemplate.exchange(
                "/users/" + targetSub + "/follow", HttpMethod.DELETE, authenticated(token), Void.class);
    }

    private HttpEntity<Void> authenticated(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }
}
