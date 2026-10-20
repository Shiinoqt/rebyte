package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
class OfferAccessIT extends OrderIntegrationTestBase {
    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", () -> "http://localhost:" + STUB.port());
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @AfterAll static void stopStub() { STUB.stop(); }

    @Test
    void listsAreParticipantScopedFilterableAndStableAtEqualTimestamps() {
        String seller = "seller-" + UUID.randomUUID();
        String buyerA = "buyer-a-" + UUID.randomUUID();
        String buyerB = "buyer-b-" + UUID.randomUUID();
        String stranger = "stranger-" + UUID.randomUUID();
        String l1 = UUID.randomUUID().toString();
        String l2 = UUID.randomUUID().toString();
        STUB.registerListing(l1, seller, "Amiga 500", new BigDecimal("120.00"), BigDecimal.ZERO,
                "HARDWARE", List.of("PAYPAL"), true, 1L);
        STUB.registerListing(l2, seller, "ThinkPad", new BigDecimal("130.00"), BigDecimal.ZERO,
                "HARDWARE", List.of("PAYPAL"), true, 1L);
        String a1 = create(buyerA, l1);
        String b1 = create(buyerB, l1);
        String a2 = create(buyerA, l2);

        assertThat(ids("/offers/me/sent", buyerA)).containsExactlyInAnyOrder(a1, a2);
        assertThat(ids("/offers/me/sent", buyerB)).containsExactly(b1);
        assertThat(ids("/offers/me/received", seller)).containsExactlyInAnyOrder(a1, b1, a2);
        assertThat(ids("/offers/me/received?listingId=" + l1, seller)).containsExactlyInAnyOrder(a1, b1);
        assertThat(ids("/offers/me/sent", stranger)).isEmpty();
        assertThat(ids("/offers/me/received", stranger)).isEmpty();

        jdbc.update("update offers set created_at = '2026-01-01 12:00:00.000000' where id in (?, ?, ?)",
                a1, b1, a2);
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            String url = "/offers/me/received?size=1" + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> response = get(url, seller);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
            assertThat(items).hasSize(1);
            paged.add(items.getFirst().get("id").toString());
            cursor = (String) response.getBody().get("nextCursor");
        } while (cursor != null);
        assertThat(paged).containsExactlyInAnyOrder(a1, b1, a2).doesNotHaveDuplicates();

        for (String bad : List.of("size=0", "size=51", "cursor=not-base64")) {
            ResponseEntity<Map> response = get("/offers/me/sent?" + bad, buyerA);
            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody().get("title")).isEqualTo("invalid-offer-cursor");
        }
        assertThat(get("/offers/" + a1, "admin-operator").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/offers/" + a1, buyerB).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String create(String buyer, String listing) {
        HttpHeaders headers = headers(buyer);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("/offers", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "amount", "80.00"), headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    private Set<String> ids(String url, String subject) {
        ResponseEntity<Map> response = get(url, subject);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        Set<String> result = new HashSet<>();
        for (Map<String, Object> item : items) {
            result.add(item.get("id").toString());
        }
        return result;
    }

    private ResponseEntity<Map> get(String url, String subject) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers(subject)), Map.class);
    }

    private HttpHeaders headers(String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(subject);
        return headers;
    }
}
