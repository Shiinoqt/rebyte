package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class OfferAcceptRaceIT extends OrderIntegrationTestBase {
    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @AfterAll static void stopStub() { STUB.stop(); }

    @Test
    void twoConcurrentAcceptsOfSameOfferMakeOneReserveCall() throws Exception {
        String listing = listing();
        String offer = create(listing, "same-buyer", "80.00");
        CountDownLatch release = new CountDownLatch(1);
        STUB.holdReserve(listing, release);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<ResponseEntity<Map>> first = CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return accept(offer);
            }, pool);
            CompletableFuture<ResponseEntity<Map>> second = CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return accept(offer);
            }, pool);
            awaitReserveCalls(listing, 1);
            ResponseEntity<Map> loser = first.isDone() ? first.get(5, TimeUnit.SECONDS)
                    : second.get(5, TimeUnit.SECONDS);
            assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(loser.getBody().get("title")).isEqualTo("offer-accept-in-progress");
            release.countDown();
            List<ResponseEntity<Map>> responses = List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK)).hasSize(1);
            assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId"))))
                    .hasSize(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void catalogArbitratesAcceptsOfDifferentOffersOnOneListing() throws Exception {
        String listing = listing();
        String first = create(listing, "race-first", "80.00");
        String second = create(listing, "race-second", "85.00");
        String third = create(listing, "race-third", "90.00");
        STUB.arbitrateReserve(listing);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<ResponseEntity<Map>> one = CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return accept(first);
            }, pool);
            CompletableFuture<ResponseEntity<Map>> two = CompletableFuture.supplyAsync(() -> {
                await(barrier);
                return accept(second);
            }, pool);
            List<ResponseEntity<Map>> responses = List.of(one.get(15, TimeUnit.SECONDS),
                    two.get(15, TimeUnit.SECONDS));
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.OK)).hasSize(1);
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)).hasSize(1);
            List<String> statuses = jdbc.queryForList("select status from offers where id in (?, ?, ?)",
                    String.class, first, second, third);
            assertThat(statuses).containsExactlyInAnyOrder("ACCEPTED", "REJECTED", "REJECTED");
            assertThat(STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId"))).count())
                    .isBetween(1L, 2L);
            assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where event_type = "
                    + "'offer.accepted' and aggregate_id in (?, ?, ?)", Integer.class, first, second, third))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String listing() {
        String id = UUID.randomUUID().toString();
        STUB.registerListing(id, "race-seller", "Hardware", new BigDecimal("120.00"),
                new BigDecimal("7.50"), "HARDWARE", List.of("PAYPAL"), true, 5L);
        return id;
    }

    private String create(String listing, String buyer, String amount) {
        ResponseEntity<Map> response = rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", amount), headers(buyer)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map> accept(String id) {
        return rest.exchange("/offers/" + id + "/accept", HttpMethod.POST,
                new HttpEntity<>(headers("race-seller")), Map.class);
    }

    private HttpHeaders headers(String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(subject);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private void awaitReserveCalls(String listing, int count) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (STUB.reserveCalls().stream().filter(call -> listing.equals(call.get("listingId"))).count() >= count) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("reserve call did not arrive");
    }
}
