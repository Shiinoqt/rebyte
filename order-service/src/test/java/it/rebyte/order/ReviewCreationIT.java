package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
@org.junit.jupiter.api.Timeout(120)
class ReviewCreationIT extends OrderIntegrationTestBase {

    @Autowired TestRestTemplate http;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OrderPaymentTransitionService transitions;

    @Test
    void buyerReviewsDeliveredOrderAndPublishesTheFixedEventContract() throws Exception {
        String id = seed("DELIVERED", ListingType.HARDWARE);

        ResponseEntity<Map> created = post(id, "buyer", Map.of("rating", 4, "comment", "  Works as described  "));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("rating", 4).containsEntry("comment", "Works as described")
                .doesNotContainKey("authorSub").containsEntry("sellerSub", "seller")
                .containsEntry("listingTitle", "Private title").containsKey("id").containsKey("createdAt");
        String reviewId = (String) created.getBody().get("id");

        assertThat(count("select count(*) from reviews where order_id=?", id)).isEqualTo(1L);
        assertThat(count("select count(*) from order_outbox_events where event_type='review.created' and aggregate_id=?",
                reviewId)).isEqualTo(1L);
        var payload = mapper.readTree(jdbc.queryForObject(
                "select payload from order_outbox_events where event_type='review.created' and aggregate_id=?",
                String.class, reviewId));
        var keys = new HashSet<String>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("reviewId", "sellerSub", "rating", "occurredAt");
        assertThat(payload.get("reviewId").asText()).isEqualTo(reviewId);
        assertThat(payload.get("sellerSub").asText()).isEqualTo("seller");
        assertThat(payload.get("rating").isIntegralNumber()).isTrue();
        assertThat(payload.get("rating").asInt()).isEqualTo(4);
        assertThat(payload.get("occurredAt").isTextual()).isTrue();
        assertThat(Instant.parse(payload.get("occurredAt").asText())).isNotNull();

        for (String sub : new String[] { "buyer", "seller", "admin-x" }) {
            ResponseEntity<Map> detail = request("/orders/" + id, HttpMethod.GET, sub, null);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Map<String, Object>) detail.getBody().get("review")).containsEntry("id", reviewId)
                    .containsEntry("rating", 4).containsEntry("comment", "Works as described")
                    .doesNotContainKey("authorSub");
        }
    }

    @Test
    void onlyTheBuyerOfADeliveredOrderMayReviewIt() {
        String id = seed("DELIVERED", ListingType.HARDWARE);
        assertError(post(id, "seller", validReview()), HttpStatus.FORBIDDEN, "order-action-forbidden");
        assertError(post(id, "admin-x", validReview()), HttpStatus.FORBIDDEN, "order-action-forbidden");
        assertError(post(id, "stranger", validReview()), HttpStatus.NOT_FOUND, "order-not-found");
        assertError(post(UUID.randomUUID().toString(), "buyer", validReview()), HttpStatus.NOT_FOUND,
                "order-not-found");
        assertThat(count("select count(*) from reviews where order_id=?", id)).isZero();

        for (String status : List.of("PAID", "SHIPPED", "AWAITING_PAYMENT", "CANCELLED")) {
            String notDelivered = seed(status, ListingType.HARDWARE);
            assertError(post(notDelivered, "buyer", validReview()), HttpStatus.CONFLICT, "order-not-reviewable");
            assertThat(count("select count(*) from reviews where order_id=?", notDelivered)).isZero();
        }
        String refundedAfterDelivery = seed("DELIVERED", ListingType.HARDWARE);
        refund(refundedAfterDelivery);
        assertThat(orders.findById(refundedAfterDelivery).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertError(post(refundedAfterDelivery, "buyer", validReview()), HttpStatus.CONFLICT,
                "order-not-reviewable");

        assertThat(post(id, "buyer", validReview()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertError(post(id, "buyer", validReview()), HttpStatus.CONFLICT, "review-already-exists");
        assertThat(count("select count(*) from reviews where order_id=?", id)).isEqualTo(1L);
    }

    @Test
    void concurrentReviewsOfOneOrderHaveExactlyOneWinner() throws Exception {
        String id = seed("DELIVERED", ListingType.HARDWARE);
        CyclicBarrier barrier = new CyclicBarrier(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException("barrier wait failed", e);
                    }
                    return post(id, "buyer", validReview());
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            List<ResponseEntity<Map>> responses = futures.stream().map(future -> future.getNow(null)).toList();
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count()).isEqualTo(1);
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count()).isEqualTo(7);
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                    .map(r -> r.getBody().toString())).allSatisfy(body -> assertThat(body)
                            .contains("review-already-exists"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from reviews where order_id=?", id)).isEqualTo(1L);
        assertThat(count("select count(*) from order_outbox_events where event_type='review.created' "
                + "and payload like ?", "%" + jdbc.queryForObject("select id from reviews where order_id=?",
                        String.class, id) + "%")).isEqualTo(1L);
    }

    @Test
    void ratingBoundariesAreExactAndNeverTruncated() {
        for (int accepted : new int[] { 0, 5 }) {
            String id = seed("DELIVERED", ListingType.HARDWARE);
            ResponseEntity<Map> response = post(id, "buyer", Map.of("rating", accepted));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).containsEntry("rating", accepted);
            assertThat(jdbc.queryForObject("select rating from reviews where order_id=?", Integer.class, id))
                    .isEqualTo(accepted);
        }

        List<Map<String, Object>> rejected = List.of(Map.of("rating", -1), Map.of("rating", 6),
                Map.of("comment", "no rating"), Map.of("rating", 4.5), Map.of("rating", 4.0));
        for (Map<String, Object> body : rejected) {
            String id = seed("DELIVERED", ListingType.HARDWARE);
            assertThat(post(id, "buyer", body).getStatusCode()).as(body.toString()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(count("select count(*) from reviews where order_id=?", id)).isZero();
        }
    }

    @Test
    void commentIsOptionalTrimmedCappedAndFreeOfControlCharacters() {
        String blank = seed("DELIVERED", ListingType.HARDWARE);
        assertThat(post(blank, "buyer", Map.of("rating", 3, "comment", "  \t \n ")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select comment is null from reviews where order_id=?", Boolean.class, blank))
                .isTrue();

        String full = seed("DELIVERED", ListingType.HARDWARE);
        assertThat(post(full, "buyer", Map.of("rating", 3, "comment", "x".repeat(2000))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select char_length(comment) from reviews where order_id=?", Integer.class,
                full)).isEqualTo(2000);

        String multiline = seed("DELIVERED", ListingType.HARDWARE);
        assertThat(post(multiline, "buyer", Map.of("rating", 3, "comment", "line one\nline two")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select comment from reviews where order_id=?", String.class, multiline))
                .isEqualTo("line one\nline two");

        for (String comment : List.of("x".repeat(2001), "bad" + (char) 0 + "text", "trailing" + (char) 0,
                (char) 0 + "leading")) {
            String id = seed("DELIVERED", ListingType.HARDWARE);
            assertThat(post(id, "buyer", Map.of("rating", 3, "comment", comment)).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(count("select count(*) from reviews where order_id=?", id)).isZero();
        }
    }

    @Test
    void nullAndPresentCommentPublishTheSameEventShape() throws Exception {
        String withComment = seed("DELIVERED", ListingType.HARDWARE);
        String withoutComment = seed("DELIVERED", ListingType.HARDWARE);
        String first = (String) post(withComment, "buyer", Map.of("rating", 2, "comment", "Fine")).getBody().get("id");
        String second = (String) post(withoutComment, "buyer", Map.of("rating", 2)).getBody().get("id");

        assertThat(eventKeys(first)).containsExactlyInAnyOrder("reviewId", "sellerSub", "rating", "occurredAt");
        assertThat(eventKeys(second)).isEqualTo(eventKeys(first));
        assertThat(count("select count(*) from reviews where id in (?, ?)", first, second)).isEqualTo(2L);
    }

    @Test
    void anOpenProblemReportDoesNotBlockTheReview() {
        String id = seed("DELIVERED", ListingType.HARDWARE);
        assertThat(request("/orders/" + id + "/problem", HttpMethod.POST, "buyer",
                Map.of("reason", "OTHER", "description", "Something is off")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                .isEqualTo("OPEN");
        assertThat(post(id, "buyer", validReview()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aReviewSurvivesALaterRefundOfItsOrder() {
        String id = seed("DELIVERED", ListingType.HARDWARE);
        assertThat(post(id, "buyer", Map.of("rating", 1, "comment", "Not what I wanted")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> before = jdbc.queryForMap("select * from reviews where order_id=?", id);
        long eventsBefore = count("select count(*) from order_outbox_events where event_type='review.created' "
                + "and aggregate_id=?", before.get("id"));

        refund(id);

        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(jdbc.queryForMap("select * from reviews where order_id=?", id)).isEqualTo(before);
        assertThat(count("select count(*) from order_outbox_events where event_type='review.created' "
                + "and aggregate_id=?", before.get("id"))).isEqualTo(eventsBefore);
        ResponseEntity<Map> detail = request("/orders/" + id, HttpMethod.GET, "buyer", null);
        assertThat((Map<String, Object>) detail.getBody().get("review")).containsEntry("id", before.get("id"))
                .containsEntry("rating", 1);
    }

    private void refund(String orderId) {
        transitions.apply("payment.refunded", new PaymentResultEvent(UUID.randomUUID().toString(), orderId,
                "PP-" + orderId, "CAP-" + orderId, null, false, Instant.now()));
    }

    private Map<String, Object> validReview() {
        return Map.of("rating", 4, "comment", "Good");
    }

    private HashSet<String> eventKeys(String reviewId) throws Exception {
        var payload = mapper.readTree(jdbc.queryForObject(
                "select payload from order_outbox_events where event_type='review.created' and aggregate_id=?",
                String.class, reviewId));
        var keys = new HashSet<String>();
        payload.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private void assertError(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().toString()).contains(code);
    }

    private String seed(String status, ListingType type) {
        String id = UUID.randomUUID().toString();
        orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(), type,
                "Private title", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800)));
        jdbc.update("update orders set status=?, provider_order_id=?, provider_capture_id=?, delivered_at=? where id=?",
                status, "PP-" + id, "CAP-" + id, "DELIVERED".equals(status) ? java.sql.Timestamp.from(Instant.now())
                        : null, id);
        return id;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private ResponseEntity<Map> post(String id, String sub, Object body) {
        return request("/orders/" + id + "/review", HttpMethod.POST, sub, body);
    }

    private ResponseEntity<Map> request(String path, HttpMethod method, String sub, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(sub));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
