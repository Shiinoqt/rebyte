package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import feign.FeignException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.catalog.client.UserServiceClient;
import it.rebyte.catalog.client.UserSummary;
import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.dto.AttributeValueInput;
import it.rebyte.catalog.dto.PublishSoftwareRequest;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.error.CategoryRootMismatchException;
import it.rebyte.catalog.error.DeveloperProfileRequiredException;
import it.rebyte.catalog.error.DeveloperServiceUnavailableException;
import it.rebyte.catalog.error.NonLeafCategoryException;
import it.rebyte.catalog.error.PhotoKeyNotOnListingException;
import it.rebyte.catalog.error.PhotoLimitExceededException;
import it.rebyte.catalog.error.InvalidLicenceKeyPatternException;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.search.ListingDocumentAssembler;
import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.commons.license.LicenseKeyPattern;
import it.rebyte.catalog.messaging.ListingChangedEvent;

@Service
public class SoftwarePublishService {
    private static final Logger log = LoggerFactory.getLogger(SoftwarePublishService.class);
    private final UserServiceClient userServiceClient;
    private final MongoTemplate mongo;
    private final CategoryRepository categories;
    private final AttributeValueValidator validator;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public SoftwarePublishService(UserServiceClient userServiceClient, MongoTemplate mongo,
            CategoryRepository categories, AttributeValueValidator validator, DomainEventRecorder events,
            PlatformTransactionManager transactionManager) {
        this.userServiceClient = userServiceClient;
        this.mongo = mongo;
        this.categories = categories;
        this.validator = validator;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The Feign gate deliberately runs before the Mongo transaction. */
    public Listing publishSoftware(PublishSoftwareRequest request, String sellerSub) {
        UserSummary summary;
        try {
            summary = userServiceClient.getSummary(sellerSub);
        } catch (FeignException | IllegalStateException failure) {
            log.error("User service unavailable while checking developer profile for {}", sellerSub, failure);
            throw new DeveloperServiceUnavailableException(failure);
        }
        if (summary == null || !summary.isDeveloper()) throw new DeveloperProfileRequiredException();
        return transactions.execute(status -> publishInTransaction(request, sellerSub));
    }

    private Listing publishInTransaction(PublishSoftwareRequest request, String sellerSub) {
        if (request.photos().size() > 5) throw new PhotoLimitExceededException(request.photos().size());
        if (!request.photos().isEmpty()) throw new PhotoKeyNotOnListingException(request.photos().size());
        if (request.licenceKeyPattern() != null && !LicenseKeyPattern.isValid(request.licenceKeyPattern())) {
            throw new InvalidLicenceKeyPatternException();
        }
        VersionNumbers.compare(request.versionNumber(), request.versionNumber());
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));
        assertRoot(category, "Software");
        List<AttributeDefinition> schema = resolveSchema(category);
        Map<String, AttributeValue> attributes = validator.validate(schema, request.attributes());
        Category updated = mongo.findAndModify(Query.query(where("id").is(category.getId()).and("subcategoryIds").size(0)),
                new Update().inc("listingCount", 1), Category.class);
        if (updated == null) throw new NonLeafCategoryException(category.getId());

        String id = UUID.randomUUID().toString();
        Version first = new Version(request.versionNumber(), Instant.now(), request.notes(), null);
        Listing listing = new Listing(id, request.title(), request.description(), request.price(), Instant.now(),
                ListingStatus.ACTIVE, request.photos(), request.paymentMethods(), sellerSub, category.getId(), null,
                new SoftwareListing(request.platforms(), List.of(first), request.licenceKeyPattern()), attributes);
        Listing saved = mongo.insert(listing);
        long seq = saved.getVersion() == null ? 0 : saved.getVersion();
        events.record(saved.getId(), RebyteExchanges.LISTING_CREATED,
                new ListingChangedEvent(saved.getId(), seq, Instant.now()), Instant.now());
        return saved;
    }

    private void assertRoot(Category category, String expected) {
        List<String> names = category.getAncestorNames();
        String root = names.isEmpty() ? category.getName() : names.get(0);
        if (!expected.equals(root)) throw new CategoryRootMismatchException(category.getId(), expected, root);
    }

    private List<AttributeDefinition> resolveSchema(Category leaf) {
        List<Category> chain = new ArrayList<>();
        Category current = leaf;
        while (current != null) {
            chain.add(current);
            current = current.getParentId() == null ? null : categories.findById(current.getParentId()).orElse(null);
        }
        Collections.reverse(chain);
        return chain.stream().flatMap(category -> category.getAttributeDefinitions().stream()).toList();
    }
}
