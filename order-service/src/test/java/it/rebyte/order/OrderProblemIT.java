package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.ArrayList;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
@org.junit.jupiter.api.Timeout(120)
class OrderProblemIT extends OrderIntegrationTestBase {

    @Autowired TestRestTemplate http;
    @Autowired OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void buyerOpensOnePrivateReportVisibleToOrderParties() throws Exception {
        String id = seed("SHIPPED", ListingType.HARDWARE);
        var opened = post(id, "buyer", Map.of("reason", "NOT_RECEIVED", "description", "  Parcel never arrived  "));
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(opened.getBody()).containsEntry("orderId", id).containsEntry("reason", "NOT_RECEIVED")
                .containsEntry("status", "OPEN").containsEntry("description", "Parcel never arrived");

        for (String sub : new String[] { "buyer", "seller", "admin-operator" }) {
            var detail = request("/orders/" + id, HttpMethod.GET, sub, null);
            assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Map<String, Object>) detail.getBody().get("problem"))
                    .containsEntry("id", opened.getBody().get("id"))
                    .containsEntry("description", "Parcel never arrived");
        }
        assertProblemEvent(id);
        assertProblemError(post(id, "seller", Map.of("reason", "OTHER", "description", "seller report")),
                HttpStatus.FORBIDDEN, "order-action-forbidden");
        assertProblemError(post(id, "admin-operator", Map.of("reason", "OTHER", "description", "admin report")),
                HttpStatus.FORBIDDEN, "order-action-forbidden");
        assertProblemError(post(id, "stranger", Map.of("reason", "OTHER", "description", "stranger report")),
                HttpStatus.NOT_FOUND, "order-not-found");
        assertProblemError(post(id, "buyer", Map.of("reason", "OTHER", "description", "second report")),
                HttpStatus.CONFLICT, "problem-already-reported");
    }

    @Test
    void reportsRespectOrderStatesDeliveryWindowAndRequestValidation() {
        String paid = seed("PAID", ListingType.HARDWARE);
        String shipped = seed("SHIPPED", ListingType.HARDWARE);
        String deliveredSoftware = seedDelivered(Duration.ofDays(1), ListingType.SOFTWARE);
        String insideWindow = seedDelivered(Duration.ofDays(14).minus(Duration.ofMinutes(1)));
        assertThat(orders.findById(insideWindow).orElseThrow().getDeliveredAt())
                .isAfter(Instant.now().minus(Duration.ofDays(14)));
        for (String id : List.of(paid, shipped, deliveredSoftware, insideWindow)) {
            assertThat(post(id, "buyer", validReport()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        String outsideWindow = seedDelivered(Duration.ofDays(14).plus(Duration.ofMinutes(1)));
        assertThat(orders.findById(outsideWindow).orElseThrow().getDeliveredAt())
                .isBefore(Instant.now().minus(Duration.ofDays(14)));
        assertProblemError(post(outsideWindow, "buyer", validReport()), HttpStatus.CONFLICT,
                "problem-not-reportable");
        for (String status : List.of("AWAITING_PAYMENT", "CANCELLED", "REFUNDED")) {
            assertProblemError(post(seed(status, ListingType.HARDWARE), "buyer", validReport()),
                    HttpStatus.CONFLICT, "problem-not-reportable");
        }

        String invalidOrder = seed("SHIPPED", ListingType.HARDWARE);
        assertThat(post(invalidOrder, "buyer", Map.of("reason", "ROYAL_FAILURE", "description", "valid"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(invalidOrder, "buyer", Map.of("reason", "OTHER", "description", "  "))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(invalidOrder, "buyer", Map.of("reason", "OTHER", "description", "x".repeat(2001)))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(invalidOrder, "buyer", Map.of("reason", "OTHER", "description", "bad" + (char) 0 + "text"))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var newline = post(seed("SHIPPED", ListingType.HARDWARE), "buyer",
                Map.of("reason", "OTHER", "description", "line one\nline two"));
        assertThat(newline.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void concurrentReportsHaveOneWinnerAndWithdrawalIsFinal() throws Exception {
        String racingOrder = seed("SHIPPED", ListingType.HARDWARE);
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
                    return post(racingOrder, "buyer", validReport());
                }, pool));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            List<ResponseEntity<Map>> responses = futures.stream().map(future -> future.getNow(null)).toList();
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count()).isEqualTo(1);
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count()).isEqualTo(7);
            assertThat(responses).allSatisfy(response -> assertThat(response.getStatusCode())
                    .isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
        } finally {
            pool.shutdownNow();
        }

        String withdrawOrder = seed("SHIPPED", ListingType.HARDWARE);
        post(withdrawOrder, "buyer", validReport());
        var withdrawn = request("/orders/" + withdrawOrder + "/problem/withdraw", HttpMethod.POST, "buyer", null);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withdrawn.getBody()).containsEntry("status", "WITHDRAWN").containsKey("resolvedAt");
        assertProblemError(request("/orders/" + withdrawOrder + "/problem/withdraw", HttpMethod.POST, "buyer", null),
                HttpStatus.CONFLICT, "problem-not-open");
        assertProblemError(post(withdrawOrder, "buyer", validReport()), HttpStatus.CONFLICT,
                "problem-already-reported");
        assertProblemError(request("/orders/" + seed("SHIPPED", ListingType.HARDWARE) + "/problem/withdraw",
                HttpMethod.POST, "buyer", null), HttpStatus.NOT_FOUND, "problem-not-found");
        assertProblemError(request("/orders/" + withdrawOrder + "/problem/withdraw", HttpMethod.POST, "seller", null),
                HttpStatus.FORBIDDEN, "order-action-forbidden");
    }

    private Map<String, Object> validReport() {
        return Map.of("reason", "OTHER", "description", "A valid description");
    }

    private String seedDelivered(Duration age) {
        return seedDelivered(age, ListingType.HARDWARE);
    }

    private String seedDelivered(Duration age, ListingType type) {
        String id = seed("SHIPPED", type);
        Order order = orders.findById(id).orElseThrow();
        order.markDelivered(Instant.now().minus(age));
        orders.saveAndFlush(order);
        return id;
    }

    private void assertProblemEvent(String id) throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.problem.opened")).isEqualTo(1L);
        String payload = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id=? and event_type=?",
                String.class, id, "order.problem.opened");
        var tree = mapper.readTree(payload);
        var keys = new HashSet<String>();
        tree.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("eventId", "problemId", "orderId", "listingId", "listingType",
                "buyerSub", "sellerSub", "listingTitle", "reason", "status", "outcome", "occurredAt");
        assertThat(tree.path("listingTitle").asText()).isEqualTo("Private title");
        assertThat(payload).doesNotContain("Parcel never arrived");
    }

    private String seed(String status, ListingType type) {
        String id = UUID.randomUUID().toString();
        orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(), type,
                "Private title", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800)));
        jdbc.update("update orders set status=? where id=?", status, id);
        return id;
    }

    private ResponseEntity<Map> post(String id, String sub, Object body) {
        return request("/orders/" + id + "/problem", HttpMethod.POST, sub, body);
    }

    private ResponseEntity<Map> request(String path, HttpMethod method, String sub, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(sub));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private void assertProblemError(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().toString()).contains(code);
    }
}
