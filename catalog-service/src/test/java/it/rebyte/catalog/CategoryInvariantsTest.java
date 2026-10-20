package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.error.DuplicateSiblingNameException;
import it.rebyte.catalog.error.InvalidCategoryNameException;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.CategoryService;
import it.rebyte.catalog.service.CategoryTreeCache;

/**
 * The pure rules of ch. 8's category invariants, without a container: the
 * two-roots rule, sibling homonymy, same name under different parents, and
 * the cycle check. The concurrency proofs (409 under a real race, the
 * create-vs-publish race) belong to {@link CategoryAdminIT} — a mocked
 * transaction cannot observe a write conflict.
 */
@ExtendWith(MockitoExtension.class)
class CategoryInvariantsTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private CategoryTreeCache categoryTreeCache;

    /**
     * These are pure-rule tests with no real database — {@code
     * CategoryService} still requires a {@link PlatformTransactionManager}
     * to build its internal {@code TransactionTemplate}, so this no-op
     * implementation runs the callback directly with no real transaction
     * semantics, exactly as a mocked {@code MongoTemplate} needs none.
     */
    private static final PlatformTransactionManager NOOP_TRANSACTION_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
            // no-op
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
            // no-op
        }
    };

    private CategoryService categoryService() {
        return new CategoryService(mongoTemplate, categoryRepository, NOOP_TRANSACTION_MANAGER, categoryTreeCache);
    }

    @Test
    void rootWithNameOtherThanHardwareOrSoftwareIsRejected() {
        assertThatThrownBy(() -> categoryService().create("admin-1", "Peripherals", null))
                .isInstanceOf(InvalidCategoryNameException.class);

        verifyNoInteractions(categoryRepository);
    }

    @Test
    void secondRootWithSameNameIsRejected() {
        when(mongoTemplate.insert(any(Category.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        assertThatThrownBy(() -> categoryService().create("admin-1", "Hardware", null))
                .isInstanceOf(DuplicateSiblingNameException.class);
    }

    @Test
    void sameNameUnderDifferentParentsIsAllowed() {
        Category parentA = category("parent-a", "Components", null, List.of("Hardware"));
        Category parentB = category("parent-b", "Peripherals", null, List.of("Hardware"));
        when(categoryRepository.findById("parent-a")).thenReturn(Optional.of(parentA));
        when(categoryRepository.findById("parent-b")).thenReturn(Optional.of(parentB));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(Category.class)))
                .thenReturn(parentA, parentB);
        when(mongoTemplate.insert(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatCode(() -> categoryService().create("admin-1", "Accessori", "parent-a")).doesNotThrowAnyException();
        assertThatCode(() -> categoryService().create("admin-1", "Accessori", "parent-b")).doesNotThrowAnyException();
    }

    @Test
    void siblingHomonymyUnderSameParentIsRejected() {
        Category parent = category("parent-a", "Components", null, List.of("Hardware"));
        when(categoryRepository.findById("parent-a")).thenReturn(Optional.of(parent));
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), eq(Category.class)))
                .thenReturn(parent);
        when(mongoTemplate.insert(any(Category.class))).thenThrow(new DuplicateKeyException("E11000 duplicate key"));

        assertThatThrownBy(() -> categoryService().create("admin-1", "Accessori", "parent-a"))
                .isInstanceOf(DuplicateSiblingNameException.class);
    }

    @Test
    void categoryCannotBecomeItsOwnAncestor() {
        // A corrupted tree where the parent chain revisits itself —
        // assertAcyclic must catch this rather than loop forever or accept
        // it silently, proving plan 02-05's category move will have a real
        // guard to depend on, exercised now rather than left unverified.
        Category cycleA = category("cycle-a", "A", "cycle-b", List.of("Hardware"));
        Category cycleB = category("cycle-b", "B", "cycle-a", List.of("Hardware"));
        when(categoryRepository.findById("cycle-a")).thenReturn(Optional.of(cycleA));
        when(categoryRepository.findById("cycle-b")).thenReturn(Optional.of(cycleB));

        assertThatThrownBy(() -> categoryService().create("admin-1", "C", "cycle-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cycle detected");
    }

    @Test
    void leafFlagIsDerivedFromSubcategoryIdsWithNoSecondQuery() {
        Category leaf = category("leaf", "GPU", "components", List.of("Hardware", "Components"));
        Category nonLeaf = new Category(
                "components", "Components", "hardware", List.of("Hardware"), List.of("leaf"), List.of(),
                "admin-0", Instant.now(), 0L);

        assertThat(leaf.leaf()).isTrue();
        assertThat(nonLeaf.leaf()).isFalse();
    }

    private Category category(String id, String name, String parentId, List<String> ancestorNames) {
        return new Category(id, name, parentId, ancestorNames, List.of(), List.of(), "admin-0", Instant.now(), 0L);
    }
}
