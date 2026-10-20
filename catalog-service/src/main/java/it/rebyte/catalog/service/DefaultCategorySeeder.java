package it.rebyte.catalog.service;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.error.DuplicateSiblingNameException;
import it.rebyte.catalog.error.NonLeafCategoryException;

@Service
public class DefaultCategorySeeder {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCategorySeeder.class);
    private static final List<Seed> DEFAULTS = List.of(
            new Seed("Hardware", List.of(
                    branch("Components", "CPUs", "Graphics cards", "Motherboards", "Memory", "Storage",
                            "Power supplies", "Cases", "Cooling"),
                    branch("Computers", "Desktops", "Laptops", "Servers", "Single-board computers"),
                    branch("Peripherals", "Monitors", "Keyboards", "Mice", "Audio", "Printers"),
                    branch("Networking", "Routers", "Switches", "Network adapters"),
                    branch("Retrocomputing", "Vintage computers", "Consoles", "Parts and accessories"),
                    branch("Electronics", "Microcontrollers", "Development boards", "Sensors", "Test equipment"))),
            branch("Software", "Operating systems", "Drivers", "Firmware", "Utilities", "Development tools", "Games"));

    private final CategoryService categoryService;
    private final MongoTemplate mongoTemplate;

    public DefaultCategorySeeder(CategoryService categoryService, MongoTemplate mongoTemplate) {
        this.categoryService = categoryService;
        this.mongoTemplate = mongoTemplate;
    }

    public void seedDefaults() {
        for (Seed root : DEFAULTS) {
            Category rootCategory = findByName(root.name(), null);
            if (rootCategory == null) {
                throw new IllegalStateException("Root category not found: " + root.name());
            }
            for (Seed child : root.children()) {
                seed(child, rootCategory.getId());
            }
        }
    }

    private void seed(Seed definition, String parentId) {
        Category category = findByName(definition.name(), parentId);
        if (category == null) {
            try {
                category = categoryService.create("system-bootstrap", definition.name(), parentId);
            } catch (DuplicateSiblingNameException concurrentCreation) {
                category = findByName(definition.name(), parentId);
                if (category == null) {
                    throw new IllegalStateException("Category missing after concurrent creation: " + definition.name(),
                            concurrentCreation);
                }
            } catch (NonLeafCategoryException occupiedParent) {
                // Existing listings must retain their leaf category, even when a default branch is missing.
                logger.warn("Skipping default category {} under occupied category {}", definition.name(), parentId);
                return;
            }
        }
        for (Seed child : definition.children()) {
            seed(child, category.getId());
        }
    }

    private Category findByName(String name, String parentId) {
        Query query = Query.query(Criteria.where("name").is(name).and("parentId").is(parentId));
        return mongoTemplate.findOne(query, Category.class);
    }

    private static Seed branch(String name, String... children) {
        return new Seed(name, Arrays.stream(children).map(child -> new Seed(child, List.of())).toList());
    }

    private record Seed(String name, List<Seed> children) { }
}
