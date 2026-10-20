package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.ProblemReason;
import it.rebyte.order.repository.OrderProblemRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
@org.junit.jupiter.api.Timeout(120)
class AdminProblemQueueIT extends OrderIntegrationTestBase {

    @Autowired TestRestTemplate http;
    @Autowired OrderProblemRepository problems;

    @Test
    void malformedInstantProblemCursorKeepsValidationErrorContract() {
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "not-an-instant|order-id".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertError(get("/admin/problems?cursor=" + cursor, "admin-operator"), "invalid-problem-filter");
    }

    @Test
    void malformedInstantOrderCursorKeepsValidationErrorContract() {
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "not-an-instant|order-id".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertError(get("/admin/orders/stuck?cursor=" + cursor, "admin-operator"), "invalid-order-cursor");
    }

    @Test
    void adminQueueFiltersOrdersAndPagesEqualTimestampsWithoutGaps() {
        Instant oldest = Instant.parse("2020-01-01T00:00:00Z");
        OrderProblem open = seed("order-open", oldest, "OPEN");
        OrderProblem pending = seed("order-pending", oldest.plusSeconds(1), "REFUND_PENDING");
        OrderProblem resolved = seed("order-resolved", oldest.plusSeconds(2), "RESOLVED");
        seed("order-withdrawn", oldest.plusSeconds(3), "WITHDRAWN");

        ResponseEntity<Map> defaultQueue = get("/admin/problems", "admin-operator");
        assertThat(defaultQueue.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = items(defaultQueue);
        assertThat(items).hasSize(2).extracting(item -> item.get("id"))
                .containsExactly(open.getId(), pending.getId());
        assertThat(items.get(1)).containsEntry("resolvedBy", "admin-operator");
        assertThat(items).extracting(item -> item.get("description")).contains("Problem order-open",
                "Problem order-pending");

        assertThat(items(get("/admin/problems?status=OPEN", "admin-operator")))
                .extracting(item -> item.get("status")).containsExactly("OPEN");
        assertThat(items(get("/admin/problems?status=REFUND_PENDING", "admin-operator")))
                .extracting(item -> item.get("status")).containsExactly("REFUND_PENDING");
        assertError(get("/admin/problems?status=RESOLVED", "admin-operator"), "invalid-problem-filter");
        assertError(get("/admin/problems?size=0", "admin-operator"), "invalid-problem-filter");
        assertError(get("/admin/problems?size=51", "admin-operator"), "invalid-problem-filter");
        assertError(get("/admin/problems?cursor=not-base64", "admin-operator"), "invalid-problem-filter");

        for (OrderProblem problem : List.of(open, resolved)) {
            problems.dismiss(problem.getId(), "admin-operator", "Closed", Instant.now(),
                    it.rebyte.order.domain.ProblemStatus.OPEN, it.rebyte.order.domain.ProblemStatus.RESOLVED,
                    it.rebyte.order.domain.ProblemOutcome.DISMISS);
        }
        Instant tied = Instant.parse("2030-01-01T00:00:00Z");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(seed("order-tied-" + i, tied, "OPEN").getId());
        }
        List<String> pagedIds = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/admin/problems?status=OPEN&size=1" + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> page = get(path, "admin-operator");
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            List<Map<String, Object>> pageItems = items(page);
            assertThat(pageItems).hasSize(1);
            pagedIds.add((String) pageItems.getFirst().get("id"));
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(cursor).as("pagination must terminate").isNull();
        assertThat(pages).isEqualTo(3);
        assertThat(pagedIds).containsExactlyElementsOf(ids.stream().sorted().toList());

        assertThat(get("/admin/problems", "buyer").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> anonymous = http.exchange("/admin/problems", HttpMethod.GET,
                HttpEntity.EMPTY, Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private OrderProblem seed(String orderId, Instant createdAt, String status) {
        String id = UUID.randomUUID().toString();
        OrderProblem problem = problems.saveAndFlush(new OrderProblem(id, orderId, ProblemReason.OTHER,
                "Problem " + orderId, createdAt));
        if (status.equals("REFUND_PENDING")) {
            problems.claimRefund(id, "admin-operator", null,
                    it.rebyte.order.domain.ProblemStatus.OPEN, it.rebyte.order.domain.ProblemStatus.REFUND_PENDING);
        } else if (status.equals("RESOLVED") || status.equals("WITHDRAWN")) {
            problems.withdraw(id, Instant.now(), it.rebyte.order.domain.ProblemStatus.OPEN,
                    it.rebyte.order.domain.ProblemStatus.valueOf(status));
        }
        return problem;
    }

    private ResponseEntity<Map> get(String path, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("items");
    }

    private void assertError(ResponseEntity<Map> response, String code) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(response.getBody().toString()).contains(code);
    }
}
