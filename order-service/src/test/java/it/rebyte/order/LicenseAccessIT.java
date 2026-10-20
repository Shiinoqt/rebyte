package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.repository.LicenseRepository;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
@org.junit.jupiter.api.Timeout(120)
class LicenseAccessIT extends OrderIntegrationTestBase {

    @Autowired private TestRestTemplate rest;
    @Autowired private OrderRepository orders;
    @Autowired private LicenseRepository licenses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderPaymentTransitionService transitions;

    @Test
    void buyerReadsKeysWhileSellerAdminAndInternalReadsStayKeyFree() {
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        String sharedListing = UUID.randomUUID().toString();
        Order first = issue(buyer, seller, sharedListing);
        Order repeat = issue(buyer, seller, sharedListing);
        Order otherListing = issue(buyer, seller, UUID.randomUUID().toString());
        Order otherHolder = issue("buyer-" + UUID.randomUUID(), seller, sharedListing);
        Instant tied = Instant.parse("2026-09-30T10:00:00Z");
        jdbc.update("update licenses set issued_at = ? where order_id in (?, ?, ?)",
                java.sql.Timestamp.from(tied), first.getId(), repeat.getId(), otherListing.getId());

        Set<String> expectedKeys = Set.of(licenses.findByOrderId(first.getId()).orElseThrow().getKey(),
                licenses.findByOrderId(repeat.getId()).orElseThrow().getKey(),
                licenses.findByOrderId(otherListing.getId()).orElseThrow().getKey());
        Set<String> pageKeys = new HashSet<>();
        List<String> pageOrderIds = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> page = get("/licences/me?size=1" + (cursor == null ? "" : "&cursor=" + cursor), buyer);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map> items = (List<Map>) page.getBody().get("items");
            assertThat(items).hasSize(1);
            assertThat(pageKeys.add(String.valueOf(items.getFirst().get("key")))).isTrue();
            pageOrderIds.add(String.valueOf(items.getFirst().get("orderId")));
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(cursor).as("licence pagination must terminate").isNull();
        assertThat(pages).isEqualTo(3);
        assertThat(pageKeys).containsExactlyInAnyOrderElementsOf(expectedKeys);
        assertThat(pageOrderIds).isSortedAccordingTo((left, right) -> right.compareTo(left));
        assertThat(get("/licences/me", "buyer-with-no-licences").getBody().get("items")).isEqualTo(List.of());
        assertThat(get("/licences/me?size=0", buyer).getBody().get("title")).isEqualTo("invalid-licence-cursor");
        assertThat(get("/licences/me?size=51", buyer).getBody().get("title")).isEqualTo("invalid-licence-cursor");
        assertThat(get("/licences/me?cursor=bad", buyer).getBody().get("title")).isEqualTo("invalid-licence-cursor");

        String firstKey = licenses.findByOrderId(first.getId()).orElseThrow().getKey();
        ResponseEntity<Map> buyerOrder = get("/orders/" + first.getId(), buyer);
        assertThat(((Map) buyerOrder.getBody().get("license")).get("key"))
                .isEqualTo(licenses.findByOrderId(first.getId()).orElseThrow().getKey());
        ResponseEntity<Map> sellerOrder = get("/orders/" + first.getId(), seller);
        Map sellerLicense = (Map) sellerOrder.getBody().get("license");
        assertThat(sellerLicense).containsKey("status").doesNotContainKey("key");
        ResponseEntity<Map> adminOrder = get("/orders/" + first.getId(), "admin-admin-x");
        Map adminLicense = (Map) adminOrder.getBody().get("license");
        assertThat(adminLicense).containsKey("status").doesNotContainKey("key");

        ResponseEntity<Map> active = get("/internal/licences?buyerSub=" + buyer + "&listingId=" + sharedListing);
        assertThat(active.getBody().get("active")).isEqualTo(true);
        assertThat(get("/internal/licences?buyerSub=absent&listingId=" + sharedListing)
                .getBody().get("active")).isEqualTo(false);
        ResponseEntity<Map> missing = get("/internal/licences?buyerSub=" + buyer);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        List<String> holderSubs = new ArrayList<>();
        cursor = null;
        pages = 0;
        do {
            ResponseEntity<Map> holders = get("/internal/licences/holders?listingId=" + sharedListing + "&size=1"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            List<String> subs = (List<String>) holders.getBody().get("buyerSubs");
            holderSubs.addAll(subs);
            cursor = (String) holders.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(cursor).as("holder pagination must terminate").isNull();
        assertThat(pages).isEqualTo(2);
        assertThat(holderSubs).hasSize(2).doesNotHaveDuplicates();
        assertThat(holderSubs).contains(buyer, otherHolder.getBuyerSub());
        for (String internalPath : List.of("/internal/licences?buyerSub=" + buyer + "&listingId=" + sharedListing,
                "/internal/licences/holders?listingId=" + sharedListing)) {
            assertThat(rest.getForObject(internalPath, String.class)).doesNotContain(expectedKeys.toArray(String[]::new));
        }
        assertThat(sellerLicense.toString()).doesNotContain(firstKey);
    }

    private Order issue(String buyer, String seller, String listingId) {
        String id = UUID.randomUUID().toString();
        String providerOrder = "PP-" + id;
        String operation = "OP-" + id;
        Order order = new Order(id, buyer, seller, listingId, ListingType.SOFTWARE, "Private software title",
                new BigDecimal("12.37"), BigDecimal.ZERO, new BigDecimal("12.37"), PaymentMethod.PAYPAL,
                "idem-" + id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800));
        orders.saveAndFlush(order);
        jdbc.update("update orders set checkout_phase = ?, provider_order_id = ?, capture_operation_id = ? where id = ?",
                CheckoutPhase.CAPTURE_AUTHORIZED.name(), providerOrder, operation, id);
        transitions.apply("payment.succeeded", new PaymentResultEvent(UUID.randomUUID().toString(), id, providerOrder,
                "CAP-" + id, operation, false, Instant.now()));
        return orders.findById(id).orElseThrow();
    }

    private ResponseEntity<Map> get(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> get(String path) {
        return rest.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, Map.class);
    }
}
