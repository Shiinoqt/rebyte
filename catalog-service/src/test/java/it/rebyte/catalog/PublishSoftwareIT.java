package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.outbox.OutboxRelay;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class PublishSoftwareIT extends CatalogInfraTestBase {

    @Autowired TestRestTemplate restTemplate;
    @Autowired CategoryRepository categories;
    @Autowired ListingRepository listings;
    @Autowired OutboxRelay outboxRelay;
    @MockitoBean UserServiceClient users;

    @Test
    void developerPublishesSoftwareWithFirstVersionAndSearchProjection() {
        String seller = UUID.randomUUID().toString();
        when(users.getSummary(seller)).thenReturn(summary(seller, true));
        Category category = softwareLeaf();
        String title = "Softprobe" + UUID.randomUUID().toString().substring(0, 8);

        ResponseEntity<Map> response = publish(seller, category.getId(), title, List.of("LINUX"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = String.valueOf(response.getBody().get("id"));
        assertThat(listings.findById(id).orElseThrow().getSoftware().currentVersion().getNumber()).isEqualTo("1.0.0");

        ResponseEntity<List> versions = restTemplate.exchange("/listings/" + id + "/versions", HttpMethod.GET,
                authenticated(seller, null), List.class);
        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions.getBody()).hasSize(1);

        outboxRelay.drainUntilEmptyOrCapped();
        SearchResultPage page = pollUntilQueryReturnsAHit(title);
        assertThat(page.hits().getFirst().platforms()).containsExactly(Platform.LINUX);
        assertThat(page.hits().getFirst().currentVersion()).isEqualTo("1.0.0");
    }

    @Test
    void nonDeveloperHardwareLeafAndEmptyPlatformsAreRefused() {
        String regular = UUID.randomUUID().toString();
        when(users.getSummary(regular)).thenReturn(summary(regular, false));
        assertThat(publish(regular, softwareLeaf().getId(), "Not a developer", List.of("LINUX")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String developer = UUID.randomUUID().toString();
        when(users.getSummary(developer)).thenReturn(summary(developer, true));
        assertThat(publish(developer, hardwareLeaf().getId(), "Wrong root", List.of("LINUX")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(publish(developer, softwareLeaf().getId(), "No platforms", List.of()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map> publish(String seller, String categoryId, String title, List<String> platforms) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "A useful software package");
        body.put("price", "9.99");
        body.put("categoryId", categoryId);
        body.put("photos", List.of());
        body.put("paymentMethods", List.of("PAYPAL"));
        body.put("platforms", platforms);
        body.put("versionNumber", "1.0.0");
        body.put("notes", "Initial release");
        body.put("attributes", List.of());
        return restTemplate.exchange("/listings/software", HttpMethod.POST, authenticated(seller, body), Map.class);
    }

    private HttpEntity<?> authenticated(String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestJwtSupport.mint(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private Category softwareLeaf() {
        return categories.save(new Category(UUID.randomUUID().toString(), "Tools-" + UUID.randomUUID(), null,
                List.of("Software"), List.of(), List.of(), "admin", Instant.now(), 0L));
    }

    private Category hardwareLeaf() {
        return categories.save(new Category(UUID.randomUUID().toString(), "GPU-" + UUID.randomUUID(), null,
                List.of("Hardware"), List.of(), List.of(), "admin", Instant.now(), 0L));
    }

    private UserSummary summary(String subject, boolean developer) {
        return new UserSummary(subject, "user", null, developer, null, 0);
    }

    private SearchResultPage pollUntilQueryReturnsAHit(String query) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            ResponseEntity<SearchResultPage> response = restTemplate.getForEntity("/search?q=" + query, SearchResultPage.class);
            if (response.getBody() != null && !response.getBody().hits().isEmpty()) return response.getBody();
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("software listing was not indexed");
    }
}
