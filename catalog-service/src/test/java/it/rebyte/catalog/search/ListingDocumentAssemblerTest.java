package it.rebyte.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.EffectiveSchemaResolver;

/** Pins the complete search-document shape and the reserved {@code attr} namespace. */
@ExtendWith(MockitoExtension.class)
class ListingDocumentAssemblerTest {

    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "id", "title", "description", "price", "listingType", "status", "categoryId",
            "categoryPathIds", "categoryPath", "sellerSub", "sellerUsername", "publishedAt", "condition",
            "shippingLocation", "platforms", "currentVersion", "coverPhotoKey", "seq", "attr");

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private EffectiveSchemaResolver effectiveSchemaResolver;

    @Test
    void assembledDocumentHasExactlyTheExpectedFieldsAndNoStrayAttrPrefix() throws Exception {
        Category category = new Category(
                "cat-1", "GPU", "cat-0", List.of("Hardware", "Components"),
                List.of(), List.of(), "test-admin", Instant.now(), 0L);
        when(categoryRepository.findById("cat-1")).thenReturn(Optional.of(category));
        when(categoryRepository.findById("cat-0")).thenReturn(Optional.of(new Category(
                "cat-0", "Components", "cat-root", List.of("Hardware"), List.of("cat-1"), List.of(),
                "test-admin", Instant.now(), 0L)));
        when(categoryRepository.findById("cat-root")).thenReturn(Optional.of(new Category(
                "cat-root", "Hardware", null, List.of(), List.of("cat-0"), List.of(),
                "test-admin", Instant.now(), 0L)));

        AttributeDefinition vramGb =
                new AttributeDefinition("def-vram", "vram_gb", AttributeType.INTEGER, false, List.of(), "GB", Instant.now());
        when(effectiveSchemaResolver.effectiveSchema("cat-1")).thenReturn(List.of(vramGb));

        Listing listing = new Listing(
                UUID.randomUUID().toString(), "RTX 3080", "Used, works fine", new BigDecimal("450.00"),
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER),
                "seller-sub-1", "cat-1",
                new HardwareListing(Condition.VERY_GOOD, "Milano", BigDecimal.ZERO), null,
                Map.of("def-vram", AttributeValue.ofInteger(16)));

        ListingDocumentAssembler assembler = new ListingDocumentAssembler(categoryRepository, effectiveSchemaResolver);
        ListingDocument document = assembler.assemble(listing, 0L);

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var fieldNames = objectMapper.convertValue(document, java.util.Map.class).keySet();

        assertThat(fieldNames).isEqualTo(EXPECTED_FIELDS);
        assertThat(fieldNames)
                .as("no field other than the reserved attr namespace may begin with attr")
                .filteredOn(name -> !"attr".equals(name))
                .noneMatch(name -> name.toString().startsWith("attr"));
        assertThat(document.categoryPath()).containsExactly("Hardware", "Components", "GPU");
        assertThat(document.categoryPathIds()).containsExactly("cat-root", "cat-0", "cat-1");
        assertThat(document.attr()).containsExactly(Map.entry("vram_gb", 16L));
    }

    @Test
    void coverPhotoKeyIsTheFirstStoredPhotoOrNullWhenThereAreNone() {
        Category category = new Category(
                "cat-3", "Amiga", null, List.of(), List.of(), List.of(), "test-admin", Instant.now(), 0L);
        when(categoryRepository.findById("cat-3")).thenReturn(Optional.of(category));
        ListingDocumentAssembler assembler = new ListingDocumentAssembler(categoryRepository, effectiveSchemaResolver);

        ListingDocument withPhotos = assembler.assemble(listingWithPhotos(List.of("k1", "k2")), 0L);
        ListingDocument withoutPhotos = assembler.assemble(listingWithPhotos(List.of()), 0L);

        assertThat(withPhotos.coverPhotoKey()).isEqualTo("k1");
        assertThat(withoutPhotos.coverPhotoKey()).isNull();
    }

    private static Listing listingWithPhotos(List<String> photos) {
        return new Listing(
                UUID.randomUUID().toString(), "Amiga 500", "Boots fine", new BigDecimal("120.00"),
                Instant.now(), ListingStatus.ACTIVE, photos, Set.of(PaymentMethod.BANK_TRANSFER),
                "seller-sub-3", "cat-3",
                new HardwareListing(Condition.GOOD, "Roma", BigDecimal.ZERO), null, Map.of());
    }

    @Test
    void emptyAttributeMapProjectsToAnEmptyAttrObjectWithoutCallingTheResolver() {
        Category category = new Category(
                "cat-2", "Amiga", "cat-0", List.of("Hardware"), List.of(), List.of(), "test-admin", Instant.now(), 0L);
        when(categoryRepository.findById("cat-2")).thenReturn(Optional.of(category));
        when(categoryRepository.findById("cat-0")).thenReturn(Optional.of(new Category(
                "cat-0", "Hardware", null, List.of(), List.of("cat-2"), List.of(),
                "test-admin", Instant.now(), 0L)));

        Listing listing = new Listing(
                UUID.randomUUID().toString(), "Amiga 500", "Boots fine", new BigDecimal("120.00"),
                Instant.now(), ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.BANK_TRANSFER),
                "seller-sub-2", "cat-2",
                new HardwareListing(Condition.GOOD, "Roma", BigDecimal.ZERO), null, Map.of());

        ListingDocumentAssembler assembler = new ListingDocumentAssembler(categoryRepository, effectiveSchemaResolver);
        ListingDocument document = assembler.assemble(listing, 0L);

        assertThat(document.attr()).isEmpty();
    }
}
