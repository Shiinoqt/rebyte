package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.messaging.dto.ConversationPage;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessagePage;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = {
        "rebyte.outbox.relay.fixed-delay-ms=3600000",
        "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "rebyte.outbox.purge.cron=0 0 0 1 1 *"
})
class ConversationInboxIT extends MessagingTestBase {

    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final StubHttpServer USERS = new StubHttpServer();

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", CATALOG::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
    }

    @AfterAll
    static void stopServers() {
        CATALOG.close();
        USERS.close();
    }

    @Autowired TestRestTemplate rest;

    @Test
    void inboxPagesNewestFirstWithOneBatchedUsernameCallPerPage() {
        String alice = sub("alice");
        List<String> sellers = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String seller = sub("seller" + i);
            sellers.add(seller);
            ids.add(start(alice, seller, "Messaggio " + i));
            // Distinct lastMessageAt values make the expected order independent of the random id tie-break.
            pause(5);
        }
        StringBuilder summaries = new StringBuilder("[");
        for (int i = 0; i < sellers.size(); i++) {
            summaries.append(i == 0 ? "" : ",").append("{\"sub\":\"").append(sellers.get(i))
                    .append("\",\"username\":\"Venditore").append(i).append("\"}");
        }
        USERS.respond("/internal/users", 200, summaries.append("]").toString());
        USERS.clearRequests();

        List<ConversationView> seen = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            ConversationPage page = list(alice, cursor, 2).getBody();
            pageSizes.add(page.items().size());
            seen.addAll(page.items());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(seen).extracting(ConversationView::id).containsExactlyElementsOf(ids.reversed());
        for (ConversationView view : seen) {
            int index = sellers.indexOf(view.otherParticipantSub());
            assertThat(view.otherParticipantUsername()).isEqualTo("Venditore" + index);
        }
        assertThat(USERS.requests()).filteredOn(request -> request.path().equals("/internal/users")).hasSize(3);
    }

    @Test
    void userServiceFailureStillListsWithNullUsernames() {
        String buyer = sub("buyer");
        start(buyer, sub("seller"), "Ciao");
        USERS.respond("/internal/users", 500, "{}");

        ResponseEntity<ConversationPage> response = list(buyer, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().items()).singleElement()
                .satisfies(view -> assertThat(view.otherParticipantUsername()).isNull());
    }

    @Test
    void sellerSeesUnreadAndAnEmptyInboxIsEmpty() {
        String seller = sub("seller");
        start(sub("buyer"), seller, "Ciao");

        assertThat(list(seller, null, null).getBody().items()).singleElement()
                .satisfies(view -> assertThat(view.unreadFor()).isEqualTo(1));
        ConversationPage empty = list(sub("nobody"), null, null).getBody();
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
    }

    @Test
    void garbageInboxCursorIsRejected() {
        ResponseEntity<String> response = rest.exchange("/conversations?cursor=!!garbage", HttpMethod.GET,
                new HttpEntity<>(headers(sub("u"))), String.class);
        assertProblem(response, HttpStatus.BAD_REQUEST, "invalid-cursor");
        assertProblem(rest.exchange("/conversations?cursor=bm90LWEtY3Vyc29y", HttpMethod.GET,
                new HttpEntity<>(headers(sub("u"))), String.class), HttpStatus.BAD_REQUEST, "invalid-cursor");
    }

    @Test
    void historyPagesBySeqWithNoStoreAndRejectsBadCursors() {
        String buyer = sub("buyer");
        String seller = sub("seller");
        String id = start(buyer, seller, "1");
        for (int i = 2; i <= 30; i++) {
            send(i % 2 == 0 ? seller : buyer, id, Integer.toString(i));
        }

        ResponseEntity<MessagePage> first = history(buyer, id, null, 20);
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");
        assertThat(first.getBody().items()).extracting(MessageView::seq)
                .containsExactlyElementsOf(range(30, 11));
        MessagePage second = history(buyer, id, first.getBody().nextCursor(), 20).getBody();
        assertThat(second.items()).extracting(MessageView::seq).containsExactlyElementsOf(range(10, 1));
        assertThat(second.nextCursor()).isNull();

        for (String bad : List.of("", "MA", "LTE", "YWJj")) {
            assertProblem(rest.exchange("/conversations/" + id + "/messages?cursor=" + bad, HttpMethod.GET,
                    new HttpEntity<>(headers(buyer)), String.class), HttpStatus.BAD_REQUEST, "invalid-cursor");
        }
        assertProblem(rest.exchange("/conversations/" + id + "/messages", HttpMethod.GET,
                new HttpEntity<>(headers(sub("outsider"))), String.class), HttpStatus.NOT_FOUND,
                "conversation-not-found");
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<Long> range(long from, long to) {
        List<Long> values = new ArrayList<>();
        for (long value = from; value >= to; value--) {
            values.add(value);
        }
        return values;
    }

    private String start(String buyer, String seller, String text) {
        String listingId = UUID.randomUUID().toString();
        CATALOG.respond("/internal/listings/" + listingId, 200, "{\"id\":\"" + listingId + "\",\"sellerSub\":\""
                + seller + "\",\"listingTitle\":\"Oggetto\",\"price\":10.00,\"type\":\"HARDWARE\",\"available\":true}");
        Map<String, Object> body = new HashMap<>();
        body.put("listingId", listingId);
        body.put("text", text);
        ResponseEntity<ConversationView> started = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers(buyer)), ConversationView.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return started.getBody().id();
    }

    private void send(String sub, String conversationId, String text) {
        ResponseEntity<MessageView> sent = rest.exchange("/conversations/" + conversationId + "/messages",
                HttpMethod.POST, new HttpEntity<>(Map.of("text", text), headers(sub)), MessageView.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<ConversationPage> list(String sub, String cursor, Integer size) {
        StringBuilder path = new StringBuilder("/conversations?");
        if (cursor != null) {
            path.append("cursor=").append(cursor).append('&');
        }
        if (size != null) {
            path.append("size=").append(size);
        }
        return rest.exchange(path.toString(), HttpMethod.GET, new HttpEntity<>(headers(sub)), ConversationPage.class);
    }

    private ResponseEntity<MessagePage> history(String sub, String id, String cursor, int size) {
        String path = "/conversations/" + id + "/messages?size=" + size + (cursor == null ? "" : "&cursor=" + cursor);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(sub)), MessagePage.class);
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
    }

    private static void assertProblem(ResponseEntity<String> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).contains("\"title\":\"" + code + "\"");
    }

    private static String sub(String role) {
        return role + "-" + UUID.randomUUID();
    }
}
