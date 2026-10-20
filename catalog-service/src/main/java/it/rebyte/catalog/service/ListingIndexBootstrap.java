package it.rebyte.catalog.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.stereotype.Component;

import it.rebyte.catalog.domain.Listing;

/**
 * Automatic index creation from annotations is off in this module, so the
 * indexes declared on {@link Listing} are ensured here at startup.
 */
@Component
public class ListingIndexBootstrap implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    public ListingIndexBootstrap(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        IndexResolver resolver = new MongoPersistentEntityIndexResolver(mongoTemplate.getConverter().getMappingContext());
        IndexOperations indexOps = mongoTemplate.indexOps(Listing.class);
        resolver.resolveIndexFor(Listing.class).forEach(indexOps::ensureIndex);
    }
}
