package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.CategoryRootBootstrap;
import it.rebyte.catalog.service.DefaultCategorySeeder;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class DefaultCategorySeederIT extends MongoTestBase {

    @Autowired CategoryRepository categories;
    @Autowired DefaultCategorySeeder seeder;
    @Autowired CategoryRootBootstrap bootstrap;
    @Autowired MongoTemplate mongo;

    @Test
    void startupSeedsTheTreeAndRepeatedBootstrapPreservesEveryDocumentAndParentLink() {
        List<Category> initial = categories.findAll();
        assertThat(initial).hasSize(41);
        Map<String, Category> byId = initial.stream().collect(Collectors.toMap(Category::getId, Function.identity()));
        assertThat(initial.stream().filter(category -> category.getParentId() == null).map(Category::getName))
                .containsExactlyInAnyOrder("Hardware", "Software");
        assertThat(initial.stream().filter(Category::leaf).count()).isEqualTo(33);
        for (Category category : initial) {
            if (category.getParentId() != null) {
                Category parent = byId.get(category.getParentId());
                assertThat(parent.getSubcategoryIds()).contains(category.getId());
                assertThat(category.getAncestorNames())
                        .containsExactlyElementsOf(append(parent.getAncestorNames(), category.getName()));
            }
        }

        Map<String, Object> before = documents();
        bootstrap.run(null);
        seeder.seedDefaults();

        assertThat(documents()).containsExactlyInAnyOrderEntriesOf(before);
        assertExistingListingsPreventBranchExpansion();
    }

    private void assertExistingListingsPreventBranchExpansion() {
        Category components = categories.findAll().stream()
                .filter(category -> category.getName().equals("Components")).findFirst().orElseThrow();
        List<String> childIds = components.getSubcategoryIds();
        categories.deleteAllById(childIds);
        mongo.updateFirst(Query.query(Criteria.where("id").is(components.getId())),
                new Update().set("subcategoryIds", List.of()).set("listingCount", 1L), Category.class);
        Map<String, Object> before = documents();

        seeder.seedDefaults();

        assertThat(documents()).containsExactlyInAnyOrderEntriesOf(before);
        Category preserved = categories.findById(components.getId()).orElseThrow();
        assertThat(preserved.getListingCount()).isEqualTo(1L);
        assertThat(preserved.leaf()).isTrue();
        assertThat(categories.findAll().stream().map(Category::getName)).contains("Utilities", "Laptops");
    }

    private Map<String, Object> documents() {
        return mongo.findAll(org.bson.Document.class, "category").stream()
                .collect(Collectors.toMap(document -> document.getString("_id"), document -> document));
    }

    private List<String> append(List<String> path, String name) {
        var result = new java.util.ArrayList<>(path);
        result.add(name);
        return result;
    }
}
