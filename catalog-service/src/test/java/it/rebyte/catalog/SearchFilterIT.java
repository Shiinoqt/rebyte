package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.search.ListingDocument;
import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class SearchFilterIT extends CatalogInfraTestBase {

    @Autowired TestRestTemplate http;
    @Autowired CategoryRepository categories;
    @Autowired CategoryTreeCache categoryTreeCache;
    @Autowired Index index;
    @Autowired ObjectMapper mapper;

    @Test
    void subtreeTypeInclusivePriceAndPaginationFiltersWorkAgainstTheLiveIndex() throws Exception {
        String token = "filter" + UUID.randomUUID().toString().replace("-", "");
        Category leaf = category(token + "-leaf", token + " leaf", token + "-root", List.of(token + " root"), List.of());
        Category root = category(token + "-root", token + " root", null, List.of(), List.of(leaf.getId()));
        Category otherLeaf = category(token + "-other-leaf", leaf.getName(), token + "-other-root",
                List.of(token + " other root"), List.of());
        Category otherRoot = category(token + "-other-root", token + " other root", null, List.of(),
                List.of(otherLeaf.getId()));
        categories.saveAll(List.of(root, leaf, otherRoot, otherLeaf));
        categoryTreeCache.evict();

        add(document(token + "-below", token, leaf, ListingType.HARDWARE, new BigDecimal("9.99")));
        add(document(token + "-minimum", token, leaf, ListingType.HARDWARE, new BigDecimal("10.00")));
        add(document(token + "-maximum", token, leaf, ListingType.HARDWARE, new BigDecimal("20.00")));
        add(document(token + "-above", token, leaf, ListingType.HARDWARE, new BigDecimal("20.01")));
        add(document(token + "-software", token, leaf, ListingType.SOFTWARE, new BigDecimal("15.00")));
        add(document(token + "-precision", token, leaf, ListingType.HARDWARE, new BigDecimal("0.10")));
        add(document(token + "-other-branch", token, otherLeaf, ListingType.HARDWARE, new BigDecimal("15.00")));
        add(document(token + "-sold", token, leaf, ListingType.HARDWARE, new BigDecimal("15.00"), ListingStatus.SOLD));

        String uri = UriComponentsBuilder.fromPath("/search")
                .queryParam("q", token)
                .queryParam("categoryId", root.getId())
                .queryParam("listingType", "HARDWARE")
                .queryParam("minPrice", "10.00")
                .queryParam("maxPrice", "20.00")
                .queryParam("size", "1000")
                .toUriString();
        SearchResultPage page = http.getForObject(uri, SearchResultPage.class);

        assertThat(page.hits()).extracting(hit -> hit.id())
                .containsExactlyInAnyOrder(token + "-minimum", token + "-maximum");
        assertThat(page.size()).isEqualTo(100);

        SearchResultPage exact = http.getForObject(UriComponentsBuilder.fromPath("/search")
                .queryParam("q", token)
                .queryParam("minPrice", "10.00")
                .queryParam("maxPrice", "10.00")
                .toUriString(), SearchResultPage.class);
        assertThat(exact.hits()).extracting(hit -> hit.id()).containsExactly(token + "-minimum");

        assertThat(http.getForEntity("/search?minPrice=20.00&maxPrice=10.00", String.class).getStatusCode().value())
                .isEqualTo(422);
        assertThat(http.getForEntity("/search?minPrice=0.101", String.class).getStatusCode().value())
                .isEqualTo(422);

        SearchResultPage precision = http.getForObject(UriComponentsBuilder.fromPath("/search")
                .queryParam("q", token)
                .queryParam("minPrice", "0.10")
                .queryParam("maxPrice", "0.10")
                .toUriString(), SearchResultPage.class);
        assertThat(precision.hits()).extracting(hit -> hit.id()).containsExactly(token + "-precision");

        SearchResultPage subtree = http.getForObject("/search?categoryId=" + root.getId() + "&q=" + token,
                SearchResultPage.class);
        assertThat(subtree.hits()).extracting(hit -> hit.id())
                .contains(token + "-minimum")
                .doesNotContain(token + "-other-branch");

        SearchResultPage browse = http.getForObject("/categories/" + leaf.getId() + "/listings?size=100",
                SearchResultPage.class);
        assertThat(browse.hits()).extracting(hit -> hit.id())
                .contains(token + "-minimum", token + "-software")
                .doesNotContain(token + "-other-branch", token + "-sold");

        SearchResultPage allActive = http.getForObject("/search?size=100", SearchResultPage.class);
        assertThat(allActive.hits()).extracting(hit -> hit.id())
                .contains(token + "-minimum", token + "-other-branch")
                .doesNotContain(token + "-sold");

        SearchResultPage noMatches = http.getForObject(
                "/search?minPrice=999999.00&maxPrice=999999.00", SearchResultPage.class);
        assertThat(noMatches.hits()).isEmpty();
        assertThat(noMatches.total()).isZero();

        SearchResultPage first = http.getForObject("/search?q=" + token + "&size=100", SearchResultPage.class);
        SearchResultPage second = http.getForObject("/search?q=" + token + "&size=100", SearchResultPage.class);
        assertThat(second.hits()).extracting(hit -> hit.id())
                .containsExactlyElementsOf(first.hits().stream().map(hit -> hit.id()).toList());

        String conflictingAttributeFilter = UriComponentsBuilder.fromPath("/search")
                .queryParam("categoryId", leaf.getId())
                .queryParam("attr.bus", "PCIe")
                .queryParam("attr.bus.min", "1")
                .toUriString();
        assertThat(http.getForEntity(conflictingAttributeFilter, String.class).getStatusCode().value())
                .isEqualTo(422);
    }

    @Test
    void sortByPublishDateOrdersBothEndpointsAheadOfTextRankingAndRejectsUnknownValues() throws Exception {
        String token = "sort" + UUID.randomUUID().toString().replace("-", "");
        Category leaf = category(token + "-leaf", token + " leaf", token + "-root", List.of(token + " root"), List.of());
        Category root = category(token + "-root", token + " root", null, List.of(), List.of(leaf.getId()));
        categories.saveAll(List.of(root, leaf));
        categoryTreeCache.evict();

        Instant base = Instant.parse("2026-01-01T10:00:00Z");
        add(dated(token + "-oldest", token + " oldest", "plain", leaf, base));
        add(dated(token + "-middle", token + " middle", "plain", leaf, base.plusSeconds(3600)));
        add(dated(token + "-newest", "plain newest", token + " in the description", leaf, base.plusSeconds(7200)));

        List<String> newestFirst = List.of(token + "-newest", token + "-middle", token + "-oldest");
        assertThat(ids(http.getForObject("/search?q=" + token + "&sort=newest", SearchResultPage.class)))
                .containsExactlyElementsOf(newestFirst);
        assertThat(ids(http.getForObject("/search?q=" + token + "&sort=oldest", SearchResultPage.class)))
                .containsExactlyElementsOf(newestFirst.reversed());
        assertThat(ids(http.getForObject(
                "/categories/" + leaf.getId() + "/listings?sort=newest", SearchResultPage.class)))
                .containsExactlyElementsOf(newestFirst);

        for (String uri : List.of("/search?sort=popular", "/categories/" + leaf.getId() + "/listings?sort=popular")) {
            var response = http.getForEntity(uri, String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).contains("urn:rebyte:problem:invalid-sort");
        }

        assertThat(mapper.readTree(index.getRawDocument(token + "-newest")).path("publishedAt").isTextual()).isTrue();
    }

    private static List<String> ids(SearchResultPage page) {
        return page.hits().stream().map(hit -> hit.id()).toList();
    }

    private ListingDocument dated(String id, String title, String description, Category leaf, Instant publishedAt) {
        return new ListingDocument(id, title, description, new BigDecimal("10.00"), ListingType.SOFTWARE,
                ListingStatus.ACTIVE, leaf.getId(), List.of(leaf.getParentId(), leaf.getId()),
                List.of(leaf.getAncestorNames().getFirst(), leaf.getName()),
                "seller", "seller", publishedAt, null, null, List.of(), null, null, 0L, Map.of());
    }

    private Category category(String id, String name, String parentId, List<String> ancestors, List<String> children) {
        return new Category(id, name, parentId, ancestors, children, List.of(), "admin", Instant.now(), 0L);
    }

    private ListingDocument document(String id, String queryToken, Category leaf,
            ListingType type, BigDecimal price) {
        return document(id, queryToken, leaf, type, price, ListingStatus.ACTIVE);
    }

    private ListingDocument document(String id, String queryToken, Category leaf,
            ListingType type, BigDecimal price, ListingStatus status) {
        return new ListingDocument(id, queryToken + " listing", "filter fixture", price, type,
                status, leaf.getId(), List.of(leaf.getParentId(), leaf.getId()),
                List.of(leaf.getAncestorNames().getFirst(), leaf.getName()),
                "seller", "seller", Instant.now(), type == ListingType.HARDWARE ? Condition.GOOD : null,
                type == ListingType.HARDWARE ? "Torino" : null, List.of(), null, null, 0L, Map.of());
    }

    private void add(ListingDocument document) throws Exception {
        var task = index.addDocuments(mapper.writeValueAsString(List.of(document)));
        index.waitForTask(task.getTaskUid());
    }
}
