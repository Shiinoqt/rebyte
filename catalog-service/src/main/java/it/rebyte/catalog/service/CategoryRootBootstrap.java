package it.rebyte.catalog.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.stereotype.Component;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.error.DuplicateSiblingNameException;

/**
 * Seeds the two fixed roots ({@code [V.Category.fixedRoots]}) idempotently
 * on every boot, through {@link CategoryService#create}, never a direct
 * repository insert behind it — so a fresh database is usable with no
 * manual seeding step, a restart never creates a third root, and the exact
 * invariants that guard an admin's own {@code POST /categories} request
 * guard this path too.
 *
 * <p>Also provisions the compound unique index {@link Category} declares
 * ({@code @CompoundIndex} on {@code (parentId, name)}), which is what makes
 * sibling-name uniqueness (and the two-roots rule, sharing the same index)
 * atomic under concurrency. Spring Data MongoDB's automatic index creation
 * from annotations has been OFF by default since 3.0, and this module's
 * {@code MongoConfig} deliberately does not extend {@code
 * AbstractMongoClientConfiguration} to turn the blanket setting on — this
 * class resolves and creates just this one entity's declared indexes
 * programmatically instead, before either root is created.
 */
@Component
public class CategoryRootBootstrap implements ApplicationRunner {

    private static final String BOOTSTRAP_ADMIN_SUB = "system-bootstrap";

    private final CategoryService categoryService;
    private final MongoTemplate mongoTemplate;
    private final DefaultCategorySeeder defaultCategorySeeder;
    private final boolean seedDefaultsEnabled;

    public CategoryRootBootstrap(
            CategoryService categoryService, MongoTemplate mongoTemplate, DefaultCategorySeeder defaultCategorySeeder,
            @Value("${rebyte.catalog.default-categories.enabled:true}") boolean seedDefaultsEnabled) {
        this.categoryService = categoryService;
        this.mongoTemplate = mongoTemplate;
        this.defaultCategorySeeder = defaultCategorySeeder;
        this.seedDefaultsEnabled = seedDefaultsEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureCategoryIndexes();
        ensureRoot("Hardware");
        ensureRoot("Software");
        if (seedDefaultsEnabled) {
            defaultCategorySeeder.seedDefaults();
        }
    }

    private void ensureRoot(String name) {
        try {
            categoryService.create(BOOTSTRAP_ADMIN_SUB, name, null);
        } catch (DuplicateSiblingNameException alreadySeeded) {
            // Idempotent: a previous boot already created this root.
        }
    }

    private void ensureCategoryIndexes() {
        MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext =
                mongoTemplate.getConverter().getMappingContext();
        IndexResolver indexResolver = new MongoPersistentEntityIndexResolver(mappingContext);
        IndexOperations indexOps = mongoTemplate.indexOps(Category.class);
        indexResolver.resolveIndexFor(Category.class).forEach(indexOps::ensureIndex);
    }
}
