package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mongodb.MongoException;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.error.DuplicateSiblingNameException;
import it.rebyte.catalog.error.InvalidCategoryNameException;
import it.rebyte.catalog.error.NonLeafCategoryException;
import it.rebyte.catalog.repository.CategoryRepository;

/**
 * {@code create_category} (docs/rebyte_progettazione.md §9.9) — the whole
 * of ch. 8's category invariants, enforced server-side, never trusted from
 * the client.
 *
 * <p>Deliberately carries NO {@code @PreAuthorize} of its own: the ADMIN
 * gate lives on {@code CategoryController#create}, where a real
 * authenticated principal always exists. {@link
 * it.rebyte.catalog.service.CategoryRootBootstrap} calls this method
 * directly at application startup, with no HTTP request and no
 * {@code SecurityContext} at all — a method-security annotation here would
 * make that call fail with no {@code Authentication} to check.
 *
 * <p><b>Programmatic transaction with transient-error retry, not {@code
 * @Transactional} — found via genuine RED evidence, not assumed.</b> Two
 * admins concurrently creating a child under the SAME parent both perform
 * the leaf-guard {@code findAndModify} on that ONE parent document (see
 * {@link #createChild}); {@code CategoryAdminIT}'s sibling-race test
 * reproduced MongoDB aborting the loser outright with a {@code
 * WriteConflict} (error 112, labelled {@code TransientTransactionError}) —
 * a bare 500, not the clean 409 the loser is supposed to get once its retry
 * observes the winner's now-committed sibling. Mirrors {@code
 * OutboxRelay#publishNextBatch()}'s exact retry shape (02-RESEARCH.md
 * Pattern 3): retry the WHOLE {@code create} body — a fresh child id each
 * attempt, since nothing from an aborted attempt committed — up to {@value
 * #MAX_TRANSIENT_RETRIES} times before giving up.
 */
@Service
public class CategoryService {

    private static final Set<String> FIXED_ROOT_NAMES = Set.of("Hardware", "Software");
    private static final String TRANSIENT_TRANSACTION_ERROR_LABEL = "TransientTransactionError";
    private static final int MAX_TRANSIENT_RETRIES = 40;
    private static final int MAX_BACKOFF_MILLIS = 40;

    private final MongoTemplate mongoTemplate;
    private final CategoryRepository categoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final CategoryTreeCache categoryTreeCache;

    public CategoryService(
            MongoTemplate mongoTemplate, CategoryRepository categoryRepository,
            PlatformTransactionManager transactionManager, CategoryTreeCache categoryTreeCache) {
        this.mongoTemplate = mongoTemplate;
        this.categoryRepository = categoryRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.categoryTreeCache = categoryTreeCache;
    }

    /**
     * Evicts {@link CategoryTreeCache} once, only after a successful
     * (possibly retried) commit — never on a transient-error retry attempt,
     * and never on a genuine domain rejection (duplicate name, non-leaf
     * parent, invalid root name), since none of those changed anything the
     * cache could be stale about.
     */
    public Category create(String adminSub, String name, String parentId) {
        RuntimeException lastTransientFailure = null;
        for (int attempt = 1; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                Category created = transactionTemplate.execute(status ->
                        parentId == null ? createRoot(adminSub, name) : createChild(adminSub, name, parentId));
                categoryTreeCache.evict();
                return created;
            } catch (RuntimeException e) {
                if (!isTransientTransactionError(e)) {
                    throw e;
                }
                lastTransientFailure = e;
                backoffBeforeRetry();
            }
        }
        throw lastTransientFailure;
    }

    private Category createRoot(String adminSub, String name) {
        if (!FIXED_ROOT_NAMES.contains(name)) {
            throw new InvalidCategoryNameException(name);
        }

        Category root = new Category(
                UUID.randomUUID().toString(), name, null, List.of(name), List.of(), List.of(),
                adminSub, Instant.now(), 0L);
        return insertOrDuplicate(root, null);
    }

    private Category createChild(String adminSub, String name, String parentId) {
        Category parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new CategoryNotFoundException(parentId));

        assertAcyclic(parent);

        List<String> ancestorNames = new ArrayList<>(parent.getAncestorNames());
        ancestorNames.add(name);

        String childId = UUID.randomUUID().toString();
        Category child = new Category(
                childId, name, parentId, ancestorNames, List.of(), List.of(),
                adminSub, Instant.now(), 0L);

        // The leaf guard, and the race the model does not close
        // (02-RESEARCH.md Pitfall 4, T-02-02-02): conditionally append the
        // child id only if the parent still holds zero listings, in this
        // same transaction. ListingPublishService performs the
        // mirror-image conditional update on the target category before a
        // publish — requiring subcategoryIds empty before incrementing
        // listingCount — so the two writes contend on this SAME document
        // rather than being checked against a snapshot that a concurrent
        // write can invalidate a moment later.
        Query leafGuard = Query.query(where("id").is(parentId).and("listingCount").is(0L));
        Update pushChild = new Update().push("subcategoryIds", childId);
        Category updatedParent = mongoTemplate.findAndModify(leafGuard, pushChild, Category.class);
        if (updatedParent == null) {
            throw new NonLeafCategoryException(parentId);
        }

        return insertOrDuplicate(child, parentId);
    }

    private Category insertOrDuplicate(Category category, String parentId) {
        try {
            return mongoTemplate.insert(category);
        } catch (DuplicateKeyException sameNameUnderSameParent) {
            throw new DuplicateSiblingNameException(category.getName(), parentId);
        }
    }

    /**
     * {@code [V.Category.acyclicHierarchy]} — structurally unreachable
     * through creation alone (a brand-new child id can never already appear
     * as an ancestor of the parent it is being attached to), but asserted
     * explicitly anyway: plan 02-05's category move is the first operation
     * that COULD introduce a cycle, and without this guard already present
     * and exercised now, nobody would notice later that it was missing.
     */
    private void assertAcyclic(Category from) {
        Set<String> visited = new HashSet<>();
        Category current = from;
        while (current != null) {
            if (!visited.add(current.getId())) {
                throw new IllegalStateException("Cycle detected in category tree at " + current.getId());
            }
            current = current.getParentId() == null
                    ? null
                    : categoryRepository.findById(current.getParentId()).orElse(null);
        }
    }

    /**
     * Walks the exception's cause chain for a {@link MongoException} carrying
     * the {@code TransientTransactionError} label — Spring's Mongo exception
     * translator wraps the original driver exception into a generic {@code
     * DataAccessException}, so the label is only reachable via the cause
     * chain, not on the translated exception itself. Copied from {@code
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
}
