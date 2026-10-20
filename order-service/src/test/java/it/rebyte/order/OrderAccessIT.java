package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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

import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
@org.junit.jupiter.api.Timeout(120)
class OrderAccessIT extends OrderIntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void buyerSellerAndAdminCanReadWhileOutsiderGets404AndSellerAddressWaitsForPaid() {
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        Order order = createOrder(buyer, seller, Instant.now());

        ResponseEntity<Map> buyerView = get("/orders/" + order.getId(), buyer);
        assertThat(buyerView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) buyerView.getBody().get("shippingAddress")).get("postalCode")).isEqualTo("20100");

        ResponseEntity<Map> sellerView = get("/orders/" + order.getId(), seller);
        assertThat(sellerView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sellerView.getBody()).doesNotContainKey("shippingAddress");

        assertThat(get("/orders/" + order.getId(), "outsider-" + UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/orders/" + order.getId(), "admin-operator").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("update orders set status = 'PAID' where id = ?", order.getId());
        ResponseEntity<Map> paidSellerView = get("/orders/" + order.getId(), seller);
        assertThat(((Map) paidSellerView.getBody().get("shippingAddress")).get("postalCode"))
                .isEqualTo("20100");
    }

    @Test
    void lowerCaseAdminGroupIsTreatedAsAdmin() {
        Order order = createOrder("buyer-" + UUID.randomUUID(), "seller-" + UUID.randomUUID(), Instant.now());

        ResponseEntity<Map> adminView = get("/orders/" + order.getId(), "lower-admin-operator");
        assertThat(adminView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map) adminView.getBody().get("shippingAddress")).get("postalCode"))
                .isEqualTo("20100");

        jdbcTemplate.update("update orders set status = 'SHIPPED' where id = ?", order.getId());
        ResponseEntity<Map> delivery = post("/orders/" + order.getId() + "/deliver", "lower-admin-operator");
        assertThat(delivery.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delivery.getBody().get("type").toString()).endsWith("order-action-forbidden");
    }

    @Test
    void purchaseAndSaleCursorsReturnEveryOrderOnce() {
        String buyer = "buyer-" + UUID.randomUUID();
        String seller = "seller-" + UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(20);
        Set<String> expected = new HashSet<>();
        for (int index = 0; index < 5; index++) {
            expected.add(createOrder(buyer, seller, base.plusSeconds(index)).getId());
        }

        assertThat(readIds("/orders/me/purchases", buyer)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(readIds("/orders/me/sales", seller)).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(get("/orders/me/purchases?size=51", buyer).getStatusCode().value()).isEqualTo(422);
        assertThat(get("/orders/me/purchases?cursor=bad&size=2", buyer).getStatusCode().value())
                .isEqualTo(422);
        String badInstant = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-an-instant|x".getBytes(StandardCharsets.UTF_8));
        assertThat(get("/orders/me/purchases?cursor=" + badInstant, buyer).getStatusCode().value())
                .isEqualTo(422);
        assertThat(get("/orders/me/sales?cursor=" + badInstant, seller).getStatusCode().value())
                .isEqualTo(422);
    }

    @SuppressWarnings("unchecked")
    private List<String> readIds(String path, String subject) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = path + "?size=2" + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> response = get(url, subject);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
            assertThat(items).hasSizeLessThanOrEqualTo(2);
            for (Map<String, Object> item : items) {
                ids.add(String.valueOf(item.get("id")));
                if (path.endsWith("sales")) {
                    assertThat(item).doesNotContainKey("shippingAddress");
                }
            }
            cursor = (String) response.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(cursor).as("order pagination must terminate").isNull();
        assertThat(ids).doesNotHaveDuplicates();
        return ids;
    }

    private Order createOrder(String buyer, String seller, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        Order order = new Order(id, buyer, seller, UUID.randomUUID().toString(), ListingType.HARDWARE,
                "Amiga 500", new BigDecimal("100.00"), new BigDecimal("5.00"), new BigDecimal("105.00"),
                PaymentMethod.PAYPAL, "idem-" + id, UUID.randomUUID().toString(), createdAt,
                createdAt.plusSeconds(1800));
        order.applyShippingAddress("Mario Rossi", "Via Roma 1", "Milano", "20100", "IT");
        return repository.saveAndFlush(order);
    }

    private ResponseEntity<Map> get(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> post(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }
}
