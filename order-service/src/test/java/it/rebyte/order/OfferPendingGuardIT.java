package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
class OfferPendingGuardIT extends OrderIntegrationTestBase {
    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @AfterAll static void stopStub() { STUB.stop(); }

    @Test
    void uniquePendingGuardArbitratesEightConcurrentRequestsAndClearsOnExit() {
        String listing = UUID.randomUUID().toString();
        String buyer = "buyer-" + UUID.randomUUID();
        STUB.registerListing(listing, "seller-" + UUID.randomUUID(), "ThinkPad", new BigDecimal("120.00"),
                BigDecimal.ZERO, "HARDWARE", List.of("PAYPAL"), true, 1L);
        CyclicBarrier barrier = new CyclicBarrier(8);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<CompletableFuture<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        barrier.await();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    return post(buyer, listing);
                }, pool));
            }
            List<ResponseEntity<Map>> responses = futures.stream().map(CompletableFuture::join).toList();
            assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED)).hasSize(1);
            List<ResponseEntity<Map>> conflicts = responses.stream()
                    .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).toList();
            assertThat(conflicts).hasSize(7);
            assertThat(conflicts).allSatisfy(r -> assertThat(r.getBody().get("title"))
                    .isEqualTo("duplicate-pending-offer"));
            assertThat(jdbc.queryForObject("select count(*) from offers where buyer_sub = ? and listing_id = ? "
                    + "and status = 'PENDING'", Integer.class, buyer, listing)).isEqualTo(1);

            String firstId = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                    .findFirst().orElseThrow().getBody().get("id").toString();
            jdbc.update("update offers set status = 'REJECTED', rejection_reason = 'SELLER', "
                    + "pending_guard = NULL where id = ?", firstId);
            ResponseEntity<Map> next = post(buyer, listing);
            assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(next.getBody().get("id")).isNotEqualTo(firstId);
            assertThat(post("other-" + UUID.randomUUID(), listing).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<Map> post(String buyer, String listing) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(buyer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", "80.00"), headers), Map.class);
    }
}
