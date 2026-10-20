package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.Moderation;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.repository.ModerationRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.openfeign.client.config.order-service.read-timeout=500")
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class LicenseGatedDownloadIT extends MongoTestBase {
    private static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> BODY = new AtomicReference<>("{\"active\":true}");
    private static final AtomicReference<Long> DELAY_MILLIS = new AtomicReference<>(0L);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();
    private static final HttpServer ORDER_SERVICE = startServer();

    @DynamicPropertySource
    static void orderService(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.order-service.url",
                () -> "http://localhost:" + ORDER_SERVICE.getAddress().getPort());
    }

    @Autowired TestRestTemplate http;
    @Autowired ListingRepository listings;
    @Autowired ModerationRepository moderations;
    @Autowired MongoTemplate mongo;
    @MockitoBean AwsCredentialsProvider credentials;

    @BeforeEach
    void setUp() {
        STATUS.set(200);
        BODY.set("{\"active\":true}");
        DELAY_MILLIS.set(0L);
        CALLS.set(0);
        QUERIES.clear();
        doCallRealMethod().when(credentials).identityType();
        doCallRealMethod().when(credentials).resolveIdentity(org.mockito.ArgumentMatchers.any(ResolveIdentityRequest.class));
        when(credentials.resolveCredentials()).thenReturn(AwsBasicCredentials.create("test-key", "test-secret"));
    }

    @Test
    void activeLicenceCanDownloadEveryVersionWithShortLivedAttachmentUrls() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), ListingStatus.ACTIVE, true);
        String buyer = "buyer-one";
        Instant before = Instant.now().plusSeconds(120);

        Map first = download(listing, "1.0.0", buyer);
        Map later = download(listing, "2.0.0", buyer);

        assertThat(first).containsKeys("url", "expiresAt");
        assertThat(first.get("url").toString()).contains("catalog-test-bucket.s3.eu-south-1.amazonaws.com",
                "/software/" + listing.getId() + "/1.0.0/first.zip", "X-Amz-Expires=120");
        String decodedUrl = URLDecoder.decode(first.get("url").toString(), StandardCharsets.UTF_8);
        assertThat(decodedUrl).contains("response-content-disposition=attachment");
        assertThat(later.get("url").toString()).contains("/software/" + listing.getId() + "/2.0.0/later.zip");
        assertThat(Instant.parse(first.get("expiresAt").toString())).isBetween(before.minusSeconds(5), before.plusSeconds(5));
        assertThat(QUERIES).hasSize(2).allSatisfy(query -> assertThat(query)
                .contains("buyerSub=" + buyer, "listingId=" + listing.getId()));
    }

    @Test
    void onlyActiveLicenceSellerAndAdminsCanGetDownloadUrls() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), ListingStatus.ACTIVE, true);
        BODY.set("{\"active\":false}");
        assertThat(downloadResponse(listing, "1.0.0", "another-buyer").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(downloadResponse(listing, "1.0.0", listing.getSellerSub()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResponse(listing, "1.0.0", "admin-x", List.of("ADMIN")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(http.getForEntity(path(listing, "1.0.0"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void authorizationPrecedesArtifactStateAndUnknownListingOrVersionIsNotFound() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), ListingStatus.ACTIVE, false);
        BODY.set("{\"active\":true}");
        assertThat(downloadResponse(listing, "1.1.0", "active-buyer").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        BODY.set("{\"active\":false}");
        assertThat(downloadResponse(listing, "1.1.0", "inactive-buyer").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(http.exchange("/listings/missing/versions/1.0.0/download", HttpMethod.GET,
                authenticated("buyer", List.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(downloadResponse(listing, "9.0.0", "buyer").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Listing hardware = hardwareListing();
        assertThat(http.exchange(path(hardware, "1.0.0"), HttpMethod.GET,
                authenticated("buyer", List.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sellerRemovalKeepsHolderAccessButModerationBlocksOnlyNonPrivilegedCallers() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), ListingStatus.ACTIVE, true);
        BODY.set("{\"active\":true}");
        mongo.updateFirst(Query.query(Criteria.where("id").is(listing.getId())),
                new Update().set("status", ListingStatus.REMOVED), Listing.class);

        assertThat(downloadResponse(listing, "1.0.0", "active-buyer").getStatusCode()).isEqualTo(HttpStatus.OK);
        moderations.save(new Moderation(UUID.randomUUID().toString(), listing.getId(), "admin-x", Instant.now(), "Policy violation"));
        assertProblemCode(downloadResponse(listing, "1.0.0", "active-buyer"),
                HttpStatus.FORBIDDEN, "listing-moderated");
        Map sellerDownload = download(listing, "1.0.0", listing.getSellerSub());
        Map adminDownload = download(listing, "1.0.0", "admin-x", List.of("ADMIN"));
        assertThat(sellerDownload).containsKeys("url", "expiresAt");
        assertThat(adminDownload).containsKeys("url", "expiresAt");
        assertThat(sellerDownload.get("url").toString()).contains("/software/" + listing.getId() + "/1.0.0/first.zip");
        assertThat(adminDownload.get("url").toString()).contains("/software/" + listing.getId() + "/1.0.0/first.zip");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void orderServiceFailureMalformedOrEmptyLicenceResponsesNeverReturnAUrl() {
        Listing listing = softwareListing(UUID.randomUUID().toString(), ListingStatus.ACTIVE, true);
        STATUS.set(500);
        assertProblemCode(downloadResponse(listing, "1.0.0", "buyer"),
                HttpStatus.SERVICE_UNAVAILABLE, "order-service-unavailable");

        STATUS.set(200);
        BODY.set("not-json");
        assertProblemCode(downloadResponse(listing, "1.0.0", "buyer"),
                HttpStatus.SERVICE_UNAVAILABLE, "order-service-unavailable");

        BODY.set("{}");
        assertProblemCode(downloadResponse(listing, "1.0.0", "buyer"),
                HttpStatus.SERVICE_UNAVAILABLE, "order-service-unavailable");

        STATUS.set(204);
        BODY.set("");
        assertProblemCode(downloadResponse(listing, "1.0.0", "buyer"),
                HttpStatus.SERVICE_UNAVAILABLE, "order-service-unavailable");

        STATUS.set(200);
        BODY.set("{\"active\":true}");
        DELAY_MILLIS.set(900L);
        assertProblemCode(downloadResponse(listing, "1.0.0", "buyer"),
                HttpStatus.SERVICE_UNAVAILABLE, "order-service-unavailable");
    }

    private void assertProblemCode(ResponseEntity<Map> response, HttpStatus expectedStatus, String code) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus)
                .as("problem response body: %s", response.getBody());
        assertThat(response.getBody()).containsEntry("title", code);
    }

    private Map download(Listing listing, String version, String buyer) {
        return download(listing, version, buyer, List.of());
    }

    private Map download(Listing listing, String version, String buyer, List<String> groups) {
        ResponseEntity<Map> response = http.exchange(path(listing, version), HttpMethod.GET,
                authenticated(buyer, groups), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> downloadResponse(Listing listing, String version, String subject) {
        return downloadResponse(listing, version, subject, List.of());
    }

    private ResponseEntity<Map> downloadResponse(Listing listing, String version, String subject, List<String> groups) {
        return http.exchange(path(listing, version), HttpMethod.GET, authenticated(subject, groups), Map.class);
    }

    private Listing softwareListing(String seller, ListingStatus status, boolean firstHasArtifact) {
        String listingId = UUID.randomUUID().toString();
        return listings.save(new Listing(listingId, "Utility", "Useful package",
                new BigDecimal("4.99"), Instant.now(), status, List.of(), Set.of(PaymentMethod.PAYPAL),
                seller, "software-category", null, new SoftwareListing(Set.of(Platform.LINUX), List.of(
                        new Version("1.0.0", Instant.now().minusSeconds(100), "First",
                                firstHasArtifact ? "software/" + listingId + "/1.0.0/first.zip" : null),
                        new Version("2.0.0", Instant.now(), "Later", "software/" + listingId + "/2.0.0/later.zip"),
                        new Version("1.1.0", Instant.now(), "No artifact", null)), null), Map.of()));
    }

    private Listing hardwareListing() {
        return listings.save(new Listing(UUID.randomUUID().toString(), "Device", "Hardware",
                new BigDecimal("20.00"), Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL),
                UUID.randomUUID().toString(), "hardware-category",
                new it.rebyte.catalog.domain.HardwareListing(it.rebyte.catalog.domain.Condition.GOOD, "Rome", BigDecimal.ZERO),
                null, Map.of()));
    }

    private String path(Listing listing, String version) {
        return "/listings/" + listing.getId() + "/versions/" + version + "/download";
    }

    private HttpEntity<?> authenticated(String subject, List<String> groups) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject, groups));
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    @AfterAll
    static void stopOrderService() {
        ORDER_SERVICE.stop(0);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/internal/licences", exchange -> {
                CALLS.incrementAndGet();
                QUERIES.add(exchange.getRequestURI().getRawQuery());
                try {
                    Thread.sleep(DELAY_MILLIS.get());
                    String responseBody = BODY.get();
                    byte[] body = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (STATUS.get() == 204 || body.length == 0) {
                        exchange.sendResponseHeaders(STATUS.get(), -1);
                    } else {
                        exchange.sendResponseHeaders(STATUS.get(), body.length);
                        try (OutputStream output = exchange.getResponseBody()) {
                            output.write(body);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
