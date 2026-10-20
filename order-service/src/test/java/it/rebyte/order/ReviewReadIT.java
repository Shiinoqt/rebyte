package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class ReviewReadIT extends OrderIntegrationTestBase {

    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", () -> "http://localhost:" + STUB.port());
    }

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sellerReviewsAreListedNewestFirstWithOneBatchLookupPerPage() {
        String seller = "seller-" + UUID.randomUUID();
        String a = "author-a-" + UUID.randomUUID();
        String b = "author-b-" + UUID.randomUUID();
        String c = "author-c-" + UUID.randomUUID();
        STUB.registerUser(a, "Alice");
        STUB.registerUser(b, "Bob");
        STUB.registerUser(c, "Carol");
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String oldest = review(seller, a, 5, "first", base.minusSeconds(30));
        String middle = review(seller, b, 3, null, base.minusSeconds(20));
        String newest = review(seller, c, 0, "last", base.minusSeconds(10));

        ResponseEntity<Map> page = get("/users/" + seller + "/reviews", "anyone");

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = items(page);
        assertThat(items).extracting(item -> item.get("id")).containsExactly(newest, middle, oldest);
        assertThat(items).extracting(item -> item.get("authorUsername")).containsExactly("Carol", "Bob", "Alice");
        assertThat(items).allSatisfy(item -> assertThat(item).doesNotContainKey("authorSub"));
        assertThat(items.get(0)).containsEntry("rating", 0).containsEntry("comment", "last")
                .containsEntry("sellerSub", seller);
        assertThat(page.getBody().get("nextCursor")).isNull();
        assertThat(lookupsFor(a)).hasSize(1);
        assertThat(lookupsFor(a).get(0)).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void identicalTimestampsPageEachReviewOnceAndAUserLookupNeverExceedsFiftyAuthors() {
        String seller = "seller-" + UUID.randomUUID();
        Instant same = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expected.add(review(seller, "author-" + UUID.randomUUID(), 4, null, same));
        }
        expected.sort(java.util.Comparator.reverseOrder());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> page = get("/users/" + seller + "/reviews?size=1"
                    + (cursor == null ? "" : "&cursor=" + cursor), "anyone");
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(items(page)).hasSize(1);
            seen.add((String) items(page).get(0).get("id"));
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);

        String busySeller = "seller-" + UUID.randomUUID();
        String firstAuthor = "bulk-" + UUID.randomUUID();
        review(busySeller, firstAuthor, 4, null, same);
        for (int i = 1; i < 50; i++) {
            review(busySeller, "bulk-" + UUID.randomUUID(), 4, null, same.minusSeconds(i));
        }
        ResponseEntity<Map> busy = get("/users/" + busySeller + "/reviews?size=50", "anyone");
        assertThat(items(busy)).hasSize(50);
        assertThat(lookupsFor(firstAuthor)).hasSize(1);
        assertThat(lookupsFor(firstAuthor).get(0)).hasSize(50);
    }

    @Test
    void badSizesAndCursorsAreRefusedWithInvalidReviewCursor() {
        String seller = "seller-" + UUID.randomUUID();
        review(seller, "author-" + UUID.randomUUID(), 4, null, Instant.now());
        String noPipe = Base64.getUrlEncoder().withoutPadding().encodeToString("garbage".getBytes());
        String badInstant = Base64.getUrlEncoder().withoutPadding().encodeToString("not-an-instant|x".getBytes());
        for (String query : List.of("size=0", "size=51", "size=-1", "cursor=not-base64", "cursor=" + noPipe,
                "cursor=" + badInstant)) {
            ResponseEntity<Map> response = get("/users/" + seller + "/reviews?" + query, "anyone");
            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody().toString()).contains("invalid-review-cursor");
        }
    }

    @Test
    void listingFilterAppliesBeforePaginationAndNeverIncludesAnotherSellersReviews() {
        String seller = "seller-" + UUID.randomUUID();
        String listing = UUID.randomUUID().toString();
        Instant same = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expected.add(reviewForListing(seller, listing, same));
        }
        expected.sort(java.util.Comparator.reverseOrder());
        reviewForListing(seller, UUID.randomUUID().toString(), same.plusSeconds(20));
        reviewForListing("other-" + UUID.randomUUID(), listing, same.plusSeconds(30));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<Map> page = http.getForEntity("/users/" + seller + "/reviews?size=1&listingId="
                    + listing + (cursor == null ? "" : "&cursor=" + cursor), Map.class);
            assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(items(page)).hasSize(1);
            assertThat(items(page).get(0)).containsEntry("listingId", listing).containsEntry("sellerSub", seller);
            seen.add((String) items(page).get(0).get("id"));
            cursor = (String) page.getBody().get("nextCursor");
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(3);
        assertThat(items(get("/users/" + seller + "/reviews", "anyone"))).hasSize(4);
        ResponseEntity<Map> empty = get("/users/" + seller + "/reviews?listingId=" + UUID.randomUUID(), "anyone");
        assertThat(items(empty)).isEmpty();
        assertThat(empty.getBody().get("nextCursor")).isNull();
    }

    private String reviewForListing(String seller, String listingId, Instant createdAt) {
        String id = review(seller, "author-" + UUID.randomUUID(), 4, "Works well", createdAt);
        jdbc.update("update reviews set listing_id=? where id=?", listingId, id);
        return id;
    }

    @Test
    void aSingleReviewIsReadableAndAnUnknownIdIsNotFound() {
        String author = "author-" + UUID.randomUUID();
        STUB.registerUser(author, "Dana");
        String id = review("seller-" + UUID.randomUUID(), author, 2, "meh", Instant.now());

        ResponseEntity<Map> found = get("/reviews/" + id, "anyone");
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).containsEntry("id", id).containsEntry("authorUsername", "Dana")
                .containsEntry("rating", 2).containsEntry("comment", "meh").doesNotContainKey("authorSub");

        ResponseEntity<Map> missing = get("/reviews/" + UUID.randomUUID(), "anyone");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().toString()).contains("review-not-found");
    }

    @Test
    void reviewReadsSurviveAUserServiceOutageWithNullUsernames() {
        String seller = "seller-" + UUID.randomUUID();
        String author = "author-" + UUID.randomUUID();
        STUB.registerUser(author, "Erin");
        String id = review(seller, author, 5, "great", Instant.now());
        STUB.setUsersUnavailable(true);
        try {
            ResponseEntity<Map> list = get("/users/" + seller + "/reviews", "anyone");
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(items(list)).hasSize(1);
            assertThat(items(list).get(0)).containsEntry("id", id).containsEntry("authorUsername", null);

            ResponseEntity<Map> single = get("/reviews/" + id, "anyone");
            assertThat(single.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(single.getBody()).containsEntry("id", id).containsEntry("authorUsername", null);
        } finally {
            STUB.setUsersUnavailable(false);
        }
    }

    @Test
    void sellerReviewsAreReadableAnonymously() {
        String seller = "seller-" + UUID.randomUUID();
        String author = "author-" + UUID.randomUUID();
        STUB.registerUser(author, "Frank");
        String id = review(seller, author, 4, "keep", Instant.now());

        ResponseEntity<Map> page = http.getForEntity("/users/" + seller + "/reviews", Map.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(page)).hasSize(1);
        assertThat(items(page).get(0)).containsEntry("id", id).containsEntry("authorUsername", "Frank")
                .containsEntry("rating", 4).containsEntry("comment", "keep").containsEntry("sellerSub", seller)
                .doesNotContainKey("authorSub");
        assertThat(page.getBody().get("nextCursor")).isNull();
    }

    @Test
    void singleReviewReadsRequireAuthentication() {
        String id = review("seller-" + UUID.randomUUID(), "author-" + UUID.randomUUID(), 4, "keep", Instant.now());
        assertThat(http.getForEntity("/reviews/" + id, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void orderAndLicenceListsRequireAuthentication() {
        assertThat(http.getForEntity("/orders/me/purchases", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/licences/me", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reviewsCannotBeEditedOrDeleted() {
        String id = review("seller-" + UUID.randomUUID(), "author-" + UUID.randomUUID(), 4, "keep", Instant.now());

        Map<String, Object> body = Map.of("rating", 1, "comment", "changed");
        assertThat(exchange("/reviews/" + id, HttpMethod.PUT, "author", body).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange("/reviews/" + id, HttpMethod.PATCH, "author", body).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange("/reviews/" + id, HttpMethod.DELETE, "author", null).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(exchange("/reviews/" + id, HttpMethod.DELETE, "admin-root", null).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(jdbc.queryForObject("select comment from reviews where id=?", String.class, id)).isEqualTo("keep");
    }

    @Test
    void aSellerWithoutReviewsGetsAnEmptyPage() {
        ResponseEntity<Map> page = get("/users/nobody-" + UUID.randomUUID() + "/reviews", "anyone");
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(page)).isEmpty();
        assertThat(page.getBody().get("nextCursor")).isNull();
    }

    private List<List<String>> lookupsFor(String sub) {
        return STUB.userLookups().stream().filter(subs -> subs.contains(sub)).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(ResponseEntity<Map> page) {
        return (List<Map<String, Object>>) page.getBody().get("items");
    }

    private String review(String sellerSub, String buyerSub, int rating, String comment, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into reviews (id, order_id, buyer_sub, seller_sub, listing_id, listing_title, rating, "
                + "comment, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, UUID.randomUUID().toString(),
                buyerSub, sellerSub, UUID.randomUUID().toString(), "Listing title", rating, comment,
                Timestamp.from(createdAt));
        return id;
    }

    private ResponseEntity<Map> get(String path, String sub) {
        return exchange(path, HttpMethod.GET, sub, null);
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, String sub, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(sub));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
}
