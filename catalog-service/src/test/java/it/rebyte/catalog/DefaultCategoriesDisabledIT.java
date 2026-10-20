package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.support.MongoTestBase;
import it.rebyte.catalog.support.TestJwtSupport;

@SpringBootTest(properties = "rebyte.catalog.default-categories.enabled=false")
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
class DefaultCategoriesDisabledIT extends MongoTestBase {

    @Autowired CategoryRepository categories;

    @Test
    void disablingDefaultsStillBootstrapsBothFixedRoots() {
        assertThat(categories.findAll()).hasSize(2)
                .extracting(Category::getName).containsExactlyInAnyOrder("Hardware", "Software");
        assertThat(categories.findAll()).allMatch(category -> category.getParentId() == null && category.leaf());
    }
}
