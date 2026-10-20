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

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.dto.SearchResultPage;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.search.ListingDocument;
import it.rebyte.catalog.service.AttributeDefinitionService;
import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.support.CatalogInfraTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class AttributeFacetIT extends CatalogInfraTestBase {

    @Autowired TestRestTemplate http;
    @Autowired CategoryRepository categories;
    @Autowired CategoryTreeCache categoryTreeCache;
    @Autowired AttributeDefinitionService attributeDefinitions;
    @Autowired Index index;
    @Autowired ObjectMapper mapper;

    @Test
    void facetsComeFromIndexedValuesAndAttributeFiltersAreSchemaBound() throws Exception {
        String token = "facet" + UUID.randomUUID().toString().replace("-", "");
        AttributeDefinition bus = definition("bus", AttributeType.ENUM, List.of("Legacy", "Current"));
        AttributeDefinition vram = definition("vram_gb", AttributeType.INTEGER, List.of());
        AttributeDefinition rgb = definition("rgb", AttributeType.BOOLEAN, List.of());
        Category leaf = new Category(token + "-leaf", token + " leaf", null, List.of(token + " root"),
                List.of(), List.of(bus, vram, rgb), "admin", Instant.now(), 0L);
        categories.save(leaf);
        categoryTreeCache.evict();

        add(document(token + "-one", token, leaf, Map.of("bus", "Legacy", "vram_gb", 8)));
        add(document(token + "-two", token, leaf, Map.of("bus", "Legacy", "vram_gb", 16)));
        add(document(token + "-three", token, leaf, Map.of("bus", "Current", "vram_gb", 17)));

        attributeDefinitions.retireAllowedValue(leaf.getId(), bus.getId(), "Legacy");

        SearchResultPage all = get(token, leaf.getId(), Map.of());
        assertThat(all.facets()).filteredOn(facet -> facet.attribute().equals("bus"))
                .singleElement()
                .satisfies(facet -> assertThat(facet.values())
                        .containsEntry("Legacy", 2L)
                        .containsEntry("Current", 1L));

        SearchResultPage legacy = get(token, leaf.getId(), Map.of("attr.bus", "Legacy"));
        assertThat(legacy.hits()).extracting(hit -> hit.id())
                .containsExactlyInAnyOrder(token + "-one", token + "-two");

        SearchResultPage range = get(token, leaf.getId(),
                Map.of("attr.vram_gb.min", "8", "attr.vram_gb.max", "16"));
        assertThat(range.hits()).extracting(hit -> hit.id())
                .containsExactlyInAnyOrder(token + "-one", token + "-two");

        String invalid = UriComponentsBuilder.fromPath("/search")
                .queryParam("q", token).queryParam("categoryId", leaf.getId())
                .queryParam("attr.not_in_schema", "value").toUriString();
        assertThat(http.getForEntity(invalid, String.class).getStatusCode().value()).isEqualTo(422);
        assertThat(http.getForEntity("/search?attr.bus=Legacy", String.class).getStatusCode().value()).isEqualTo(422);

        String invalidBoolean = UriComponentsBuilder.fromPath("/search")
                .queryParam("q", token).queryParam("categoryId", leaf.getId())
                .queryParam("attr.rgb", "not-a-boolean").toUriString();
        assertThat(http.getForEntity(invalidBoolean, String.class).getStatusCode().value()).isEqualTo(422);
    }

    private AttributeDefinition definition(String name, AttributeType type, List<String> allowedValues) {
        return new AttributeDefinition(UUID.randomUUID().toString(), name, type, false,
                allowedValues, null, Instant.now());
    }

    private SearchResultPage get(String token, String categoryId, Map<String, String> extra) {
        UriComponentsBuilder uri = UriComponentsBuilder.fromPath("/search")
                .queryParam("q", token).queryParam("categoryId", categoryId);
        extra.forEach(uri::queryParam);
        return http.getForObject(uri.toUriString(), SearchResultPage.class);
    }

    private ListingDocument document(String id, String token, Category leaf, Map<String, Object> attributes) {
        return new ListingDocument(id, token + " listing", "facet fixture", BigDecimal.TEN,
                ListingType.HARDWARE, ListingStatus.ACTIVE, leaf.getId(),
                List.of(leaf.getId()),
                List.of(leaf.getAncestorNames().getFirst(), leaf.getName()), "seller", "seller",
                Instant.now(), Condition.GOOD, "Torino", List.of(), null, null, 0L, attributes);
    }

    private void add(ListingDocument document) throws Exception {
        var task = index.addDocuments(mapper.writeValueAsString(List.of(document)));
        index.waitForTask(task.getTaskUid());
    }
}
