package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mongodb.MongoException;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.PublishHardwareRequest;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.error.CategoryRootMismatchException;
import it.rebyte.catalog.error.NonLeafCategoryException;
import it.rebyte.catalog.error.PhotoKeyNotOnListingException;
import it.rebyte.catalog.error.PhotoLimitExceededException;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.DomainEventRecorder;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.commons.error.DomainException;
import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * {@code publish_hardware} (docs/rebyte_progettazione.md §9.3) — one
 * {@code @Transactional} method, with every external call resolved before
 * it is entered (a Feign check, an S3 call): the category lookups and the
 * schema-inheritance walk below are all local Mongo reads on the SAME
 * session, so they are safe inside the transaction, but MongoDB aborts a
 * transaction that exceeds {@code transactionLifetimeLimitSeconds}, so a
 * network call to another service would roll the domain write back with it
 * (02-RESEARCH.md Pitfall 2). This method makes no such call.
 *
 * <p><b>The effective schema is resolved from the category documents read
 * IN THIS TRANSACTION, never from {@link CategoryTreeCache}.</b> Attribute
 * validation must run against the schema as it exists at the instant of
 * this write — resolving it from the Redis-cached tree first would let a
 * concurrent schema change slip between validation and write, admitting a
 * listing that violates the schema it was supposedly checked against
 * (02-03-PLAN.md key_links).
 *
 * <p>The leaf-guard conditional update below is not in plan 02-02 Task 1's
 * own file list — its action text assigns it to "plan 02-03" — but
 * T-02-02-02 (blocking severity: high) names this exact mirror-image update
 * as ITS mitigation, and Task 1's own {@code CategoryAdminIT} acceptance
 * criterion requires proving a create-vs-publish race is closed. It was
 * added in plan 02-02 (Rule 2: a stated threat mitigation absent from the
 * implementation) rather than left for this plan to discover the proof has
 * no counterpart to race against.
 *
 * <p><b>Programmatic transaction with transient-error retry, not {@code
 * @Transactional} — found via genuine RED evidence (2026-09-23 catalog
 * todo, D-39 folded).</b> Several sellers concurrently publishing into the
 * SAME leaf category all perform the leaf-guard {@code findAndModify}
 * below on that ONE category document; {@code ConcurrentPublishIT}
 * reproduced MongoDB aborting the loser outright with a {@code
 * WriteConflict} (error 112, labelled {@code TransientTransactionError}) —
 * a bare 500, not a clean retry or a documented conflict response. Mirrors
 * {@code CategoryService#create}'s exact retry shape (itself mirroring
 * {@code OutboxRelay#publishNextBatch()}, 02-RESEARCH.md Pattern 3): retry
 * the WHOLE {@code publishHardware} body — a fresh listing id each attempt,
 * since nothing from an aborted attempt committed — up to {@value
 * #MAX_TRANSIENT_RETRIES} times before giving up and reporting a
 * documented 409, never a bare 500.
 */
@Service
public class ListingPublishService {

    private static final String HARDWARE_ROOT_NAME = "Hardware";
    private static final int MAX_PHOTOS = 5;
    private static final String TRANSIENT_TRANSACTION_ERROR_LABEL = "TransientTransactionError";
    private static final int MAX_TRANSIENT_RETRIES = 40;
    private static final int MAX_BACKOFF_MILLIS = 40;

    private final MongoTemplate mongoTemplate;
    private final CategoryRepository categoryRepository;
    private final DomainEventRecorder domainEventRecorder;
    private final AttributeValueValidator attributeValueValidator;
    private final TransactionTemplate transactionTemplate;

    public ListingPublishService(
            MongoTemplate mongoTemplate, CategoryRepository categoryRepository, DomainEventRecorder domainEventRecorder,
            AttributeValueValidator attributeValueValidator, PlatformTransactionManager transactionManager) {
        this.mongoTemplate = mongoTemplate;
        this.categoryRepository = categoryRepository;
        this.domainEventRecorder = domainEventRecorder;
        this.attributeValueValidator = attributeValueValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Retries the whole {@link #doPublishHardware} body on a transient
     * MongoDB write conflict; any other exception (a genuine domain
     * rejection such as {@link NonLeafCategoryException} or {@link
     * PhotoLimitExceededException}) propagates immediately, unretried,
     * since retrying would not change a real domain outcome. Retry
     * exhaustion — practically unreachable at {@value #MAX_TRANSIENT_RETRIES}
     * attempts — still reports a documented {@link PublishContentionExhaustedException}
     * (409) rather than letting the raw Mongo failure surface as a bare 500.
     */
    public Listing publishHardware(PublishHardwareRequest request, String sellerSub) {
        RuntimeException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> doPublishHardware(request, sellerSub));
            } catch (RuntimeException e) {
                if (!isTransientTransactionError(e)) {
                    throw e;
                }
                lastTransientFailure = e;
                backoffBeforeRetry();
            }
        }
        throw new PublishContentionExhaustedException(request.categoryId(), lastTransientFailure);
    }

    private Listing doPublishHardware(PublishHardwareRequest request, String sellerSub) {
        if (request.photos().size() > MAX_PHOTOS) {
            throw new PhotoLimitExceededException(request.photos().size());
        }
        if (!request.photos().isEmpty()) throw new PhotoKeyNotOnListingException(request.photos().size());

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(request.categoryId()));
        assertHardwareRoot(category);

        // Resolved from the category documents read in THIS transaction —
        // see class Javadoc — never from the Redis-cached whole tree.
        List<AttributeDefinition> effectiveSchema = resolveEffectiveSchemaInTransaction(category);
        Map<String, AttributeValue> attributes = attributeValueValidator.validate(effectiveSchema, request.attributes());

        // The mirror image of CategoryService's leaf-guard conditional
        // update: requires the target category to still have zero
        // subcategories at the instant of this write, incrementing the
        // same listingCount CategoryService's own conditional update reads.
        // Both writes target this SAME category document, inside their own
        // transactions — MongoDB's document-level contention is what closes
        // the race, not a snapshot read (02-RESEARCH.md Pitfall 4).
        Query leafGuard = Query.query(where("id").is(category.getId()).and("subcategoryIds").size(0));
        Update incrementListingCount = new Update().inc("listingCount", 1);
        Category updatedCategory = mongoTemplate.findAndModify(leafGuard, incrementListingCount, Category.class);
        if (updatedCategory == null) {
            throw new NonLeafCategoryException(category.getId());
        }

        Listing listing = new Listing(
                UUID.randomUUID().toString(),
                request.title(),
                request.description(),
                request.price(),
                Instant.now(),
                ListingStatus.ACTIVE,
                request.photos(),
                request.paymentMethods(),
                sellerSub,
                category.getId(),
                new HardwareListing(request.condition(), request.shippingLocation(), request.shippingCost()),
                null,
                attributes);

        Listing saved = mongoTemplate.insert(listing);

        // The aggregate sequence is the @Version value Spring Data assigned
        // on insert — no second counter (D-11).
        long seq = saved.getVersion() != null ? saved.getVersion() : 0L;
        ListingChangedEvent event = new ListingChangedEvent(saved.getId(), seq, Instant.now());
        domainEventRecorder.record(saved.getId(), RebyteExchanges.LISTING_CREATED, event, Instant.now());

        return saved;
    }

    /**
     * {@code [V.Listing.leafAndConsistentCategory]}: a hardware listing may
     * only publish into a leaf whose root is named "Hardware". {@code
     * ancestorNames}'s first entry is always the root's own name regardless
     * of whether the list is self-inclusive (as {@code CategoryService}
     * constructs it) or self-exclusive (as some test fixtures construct
     * it) — both conventions agree on element zero.
     */
    private void assertHardwareRoot(Category category) {
        List<String> ancestorNames = category.getAncestorNames();
        String rootName = ancestorNames.isEmpty() ? category.getName() : ancestorNames.get(0);
        if (!HARDWARE_ROOT_NAME.equals(rootName)) {
            throw new CategoryRootMismatchException(category.getId(), HARDWARE_ROOT_NAME, rootName);
        }
    }

    /**
     * D-01's inheritance union, walked directly against {@link
     * CategoryRepository} inside the caller's transaction — the
     * transactional twin of {@link EffectiveSchemaResolver}, which reads
     * the Redis-cached tree instead. Deliberately duplicated rather than
     * shared: the cached resolver must never be called from inside a write
     * transaction (see this class's own Javadoc), so the two have no safe
     * common implementation to factor into.
     */
    private List<AttributeDefinition> resolveEffectiveSchemaInTransaction(Category leaf) {
        List<Category> selfToRoot = new ArrayList<>();
        Category current = leaf;
        while (current != null) {
            selfToRoot.add(current);
            current = current.getParentId() == null ? null : categoryRepository.findById(current.getParentId()).orElse(null);
        }
        Collections.reverse(selfToRoot);

        List<AttributeDefinition> effective = new ArrayList<>();
        for (Category category : selfToRoot) {
            effective.addAll(category.getAttributeDefinitions());
        }
        return List.copyOf(effective);
    }

    /**
     * Walks the exception's cause chain for a {@link MongoException} carrying
     * the {@code TransientTransactionError} label — Spring's Mongo exception
     * translator wraps the original driver exception into a generic {@code
     * DataAccessException}, so the label is only reachable via the cause
     * chain, not on the translated exception itself. Copied from {@code
     * CategoryService#isTransientTransactionError} / {@code
     * OutboxRelay#isTransientTransactionError} — same mechanism, same fix.
     */
    private static boolean isTransientTransactionError(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof MongoException mongoException
                    && mongoException.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void backoffBeforeRetry() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, MAX_BACKOFF_MILLIS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reported only after {@value #MAX_TRANSIENT_RETRIES} consecutive
     * transient-write-conflict attempts on the same leaf category — a
     * documented 409, never a bare 500, even in the (practically
     * unreachable at this retry budget) exhaustion case. Declared here
     * rather than as a top-level {@code it.rebyte.catalog.error} type: this
     * plan's file list scopes the fix to this class alone, and the
     * exception has exactly one throw site.
     */
    public static class PublishContentionExhaustedException extends DomainException {

        public PublishContentionExhaustedException(String categoryId, Throwable cause) {
            super(HttpStatus.CONFLICT, "publish-contention-exhausted",
                    "Category " + categoryId + " is under heavy concurrent publish contention; retry");
            if (cause != null) {
                initCause(cause);
            }
        }
    }
}
