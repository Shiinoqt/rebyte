package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.AttributeValueInput;
import it.rebyte.catalog.dto.EditListingRequest;
import it.rebyte.catalog.error.CategoryMoveNotAllowedException;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.error.ListingNotEditableException;
import it.rebyte.catalog.error.ListingNotFoundException;
import it.rebyte.catalog.error.ListingNotOwnedException;
import it.rebyte.catalog.error.NonLeafCategoryException;
import it.rebyte.catalog.error.PhotoKeyNotOnListingException;
import it.rebyte.catalog.error.PhotoLimitExceededException;
import it.rebyte.catalog.error.InvalidLicenceKeyPatternException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.commons.license.LicenseKeyPattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.LongNode;
import tools.jackson.databind.node.StringNode;

/**
 * Implements {@code edit_listing} in one Mongo transaction. Category moves
 * resolve their schema directly from category documents, never from the
 * cached category tree, so validation and the category link change agree.
 */
@Service
public class ListingEditService {

    private static final int MAX_PHOTOS = 5;

    private final MongoTemplate mongo;
    private final CategoryRepository categories;
    private final AttributeValueValidator validator;
    private final DomainEventRecorder events;
    private final ListingCache listingCache;
    private final TransactionTemplate transactions;

    public ListingEditService(
            MongoTemplate mongo,
            CategoryRepository categories,
            AttributeValueValidator validator,
            DomainEventRecorder events,
            ListingCache listingCache,
            PlatformTransactionManager transactionManager) {
        this.mongo = mongo;
        this.categories = categories;
        this.validator = validator;
        this.events = events;
        this.listingCache = listingCache;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public EditResult edit(String sellerSub, String listingId, EditListingRequest request) {
        EditResult result = transactions.execute(ignored -> editInTransaction(sellerSub, listingId, request));
        listingCache.evict(listingId);
        return result;
    }

    private EditResult editInTransaction(String sellerSub, String listingId, EditListingRequest request) {
        Listing listing = mongo.findById(listingId, Listing.class);
        if (listing == null) throw new ListingNotFoundException(listingId);
        if (!listing.getSellerSub().equals(sellerSub)) throw new ListingNotOwnedException(listingId);
        if (listing.getStatus() != ListingStatus.ACTIVE) throw new ListingNotEditableException(listingId);
        if (request.photos().size() > MAX_PHOTOS) throw new PhotoLimitExceededException(request.photos().size());
        Set<String> submittedKeys = new HashSet<>();
        int rejectedCount = 0;
        for (String key : request.photos()) {
            if (key == null || !listing.getPhotos().contains(key) || !submittedKeys.add(key)) rejectedCount++;
        }
        if (rejectedCount > 0) throw new PhotoKeyNotOnListingException(rejectedCount);
        if (request.licenceKeyPattern() != null
                && (listing.getSoftware() == null || !LicenseKeyPattern.isValid(request.licenceKeyPattern()))) {
            throw new InvalidLicenceKeyPatternException();
        }

        String destinationId = request.categoryId() == null ? listing.getCategoryId() : request.categoryId();
        Category destination = categories.findById(destinationId)
                .orElseThrow(() -> new CategoryNotFoundException(destinationId));
        assertCompatibleRoot(listing, destination);

        boolean moving = !listing.getCategoryId().equals(destinationId);
        List<AttributeDefinition> destinationSchema = resolveEffectiveSchemaInTransaction(destination);
        Map<String, AttributeValue> attributes;
        List<String> droppedAttributeIds;
        if (moving) {
            Map<String, AttributeValue> carried = carriedValues(listing.getAttributes(), destinationSchema);
            attributes = validator.validate(destinationSchema, mergeCarriedAndSubmitted(carried, request.attributes()));
            droppedAttributeIds = droppedAttributeIds(listing.getAttributes(), carried);
            moveCategoryCounters(listing.getCategoryId(), destinationId);
        } else {
            assertLeaf(destination);
            attributes = validator.validate(destinationSchema, request.attributes());
            droppedAttributeIds = List.of();
        }

        Update update = new Update()
                .set("title", request.title())
                .set("description", request.description())
                .set("photos", request.photos())
                .set("categoryId", destinationId)
                .set("attributes", attributes);
        if (request.licenceKeyPattern() != null) update.set("software.licenceKeyPattern", request.licenceKeyPattern());

        Listing updated = mongo.findAndModify(
                Query.query(where("id").is(listingId)), update,
                FindAndModifyOptions.options().returnNew(true), Listing.class);
        if (updated == null) throw new ListingNotFoundException(listingId);

        Instant occurredAt = Instant.now();
        long sequence = updated.getVersion() == null ? 0L : updated.getVersion();
        events.record(UUID.randomUUID().toString(), RebyteExchanges.LISTING_UPDATED,
                new ListingChangedEvent(listingId, sequence, occurredAt), occurredAt);
        return new EditResult(updated, droppedAttributeIds);
    }

    private void assertCompatibleRoot(Listing listing, Category destination) {
        String actualRoot = destination.getAncestorNames().isEmpty()
                ? destination.getName()
                : destination.getAncestorNames().getFirst();
        String expectedRoot = listing.type().name().charAt(0)
                + listing.type().name().substring(1).toLowerCase();
        if (!expectedRoot.equals(actualRoot)) {
            throw new CategoryMoveNotAllowedException(destination.getId(), expectedRoot, actualRoot);
        }
    }

    private void assertLeaf(Category destination) {
        if (!destination.leaf()) throw new NonLeafCategoryException(destination.getId());
    }

    /** Conditional updates mirror publish's leaf guard and contend on both category documents. */
    private void moveCategoryCounters(String sourceId, String destinationId) {
        Category destination = mongo.findAndModify(
                Query.query(where("id").is(destinationId).and("subcategoryIds").size(0)),
                new Update().inc("listingCount", 1), Category.class);
        if (destination == null) throw new NonLeafCategoryException(destinationId);
        Category source = mongo.findAndModify(
                Query.query(where("id").is(sourceId).and("listingCount").gt(0)),
                new Update().inc("listingCount", -1), Category.class);
        if (source == null) {
            throw new IllegalStateException("source category " + sourceId + " has no listing counter to decrement");
        }
    }

    private List<AttributeDefinition> resolveEffectiveSchemaInTransaction(Category leaf) {
        List<Category> selfToRoot = new ArrayList<>();
        Category current = leaf;
        while (current != null) {
            selfToRoot.add(current);
            current = current.getParentId() == null ? null : categories.findById(current.getParentId()).orElse(null);
        }
        Collections.reverse(selfToRoot);
        List<AttributeDefinition> effective = new ArrayList<>();
        for (Category category : selfToRoot) effective.addAll(category.getAttributeDefinitions());
        return List.copyOf(effective);
    }

    /** Runs carried values through the normal validator alongside client overrides. */
    static Map<String, AttributeValue> carriedValues(
            Map<String, AttributeValue> stored, List<AttributeDefinition> destinationSchema) {
        Map<String, AttributeValue> carried = new LinkedHashMap<>();
        for (AttributeDefinition definition : destinationSchema) {
            AttributeValue existing = stored.get(definition.getId());
            if (existing != null) carried.put(definition.getId(), existing);
        }
        return Map.copyOf(carried);
    }

    static List<String> droppedAttributeIds(
            Map<String, AttributeValue> stored, Map<String, AttributeValue> carried) {
        return stored.keySet().stream().filter(attributeId -> !carried.containsKey(attributeId)).toList();
    }

    static List<AttributeValueInput> mergeCarriedAndSubmitted(
            Map<String, AttributeValue> carried, List<AttributeValueInput> submitted) {
        Map<String, AttributeValueInput> merged = new LinkedHashMap<>();
        carried.forEach((id, value) -> merged.put(id, new AttributeValueInput(id, asJson(value))));
        for (AttributeValueInput input : submitted) merged.put(input.attributeId(), input);
        return List.copyOf(merged.values());
    }

    static JsonNode asJson(AttributeValue value) {
        return switch (value.getType()) {
            case ENUM -> StringNode.valueOf(value.getStringValue());
            case INTEGER -> LongNode.valueOf(value.getIntegerValue());
            case DECIMAL -> DecimalNode.valueOf(value.getDecimalValue());
            case BOOLEAN -> BooleanNode.valueOf(value.getBooleanValue());
        };
    }

    public record EditResult(Listing listing, List<String> droppedAttributeIds) {
        public EditResult {
            droppedAttributeIds = List.copyOf(droppedAttributeIds);
        }
    }
}
