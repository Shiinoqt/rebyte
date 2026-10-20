package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.dto.SearchRequest.AttributeFilter;
import it.rebyte.catalog.error.InvalidPriceRangeException;
import it.rebyte.catalog.search.SearchFilterBuilder;
import it.rebyte.catalog.search.SearchFilterBuilder.ResolvedAttributeFilter;

class SearchFilterBuilderTest {

    @Test
    void noOptionalFiltersStillRestrictsResultsToActiveListings() {
        assertThat(SearchFilterBuilder.build(null, null, null, null, List.of()))
                .containsExactly("status = ACTIVE");
    }

    @Test
    void categoryTypeAndInclusivePriceBoundsAreComposed() {
        assertThat(SearchFilterBuilder.build("category-id", ListingType.HARDWARE,
                new BigDecimal("99.10"), new BigDecimal("199.90"), List.of()))
                .containsExactly(
                        "status = ACTIVE",
                        "categoryPathIds = \"category-id\"",
                        "listingType = HARDWARE",
                        "price >= 99.10 AND price <= 199.90");
    }

    @Test
    void equalBoundsRemainAValidExactDecimalRange() {
        assertThat(SearchFilterBuilder.range("price", new BigDecimal("19.99"), new BigDecimal("19.99")))
                .isEqualTo("price >= 19.99 AND price <= 19.99");
    }

    @Test
    void oneSidedBoundsRemainInclusive() {
        assertThat(SearchFilterBuilder.range("price", new BigDecimal("0.10"), null))
                .isEqualTo("price >= 0.10");
        assertThat(SearchFilterBuilder.range("price", null, new BigDecimal("0.30")))
                .isEqualTo("price <= 0.30");
    }

    @Test
    void invertedRangeIsRefused() {
        assertThatThrownBy(() -> SearchFilterBuilder.range(
                "price", new BigDecimal("20.00"), new BigDecimal("19.99")))
                .isInstanceOf(InvalidPriceRangeException.class);
    }

    @Test
    void callerValuesAreQuotedAndEscaped() {
        assertThat(SearchFilterBuilder.build("id\" OR status = SOLD", null, null, null, List.of()))
                .containsExactly("status = ACTIVE", "categoryPathIds = \"id\\\" OR status = SOLD\"");
    }

    @Test
    void attributeEqualityAndRangeUseOnlyResolvedFieldNames() {
        List<ResolvedAttributeFilter> attributes = List.of(
                new ResolvedAttributeFilter("bus", AttributeType.ENUM,
                        new AttributeFilter("PCIe 4.0", null, null)),
                new ResolvedAttributeFilter("vram_gb", AttributeType.INTEGER, new AttributeFilter(null,
                        new BigDecimal("8"), new BigDecimal("16"))));

        assertThat(SearchFilterBuilder.build(null, null, null, null, attributes))
                .containsExactly(
                        "status = ACTIVE",
                        "attr.bus = \"PCIe 4.0\"",
                        "attr.vram_gb >= 8 AND attr.vram_gb <= 16");
    }

    @Test
    void booleanAttributesUseBooleanLiterals() {
        assertThat(SearchFilterBuilder.build(null, null, null, null, List.of(
                new ResolvedAttributeFilter("rgb", AttributeType.BOOLEAN,
                        new AttributeFilter("TRUE", null, null)))))
                .containsExactly("status = ACTIVE", "attr.rgb = true");
    }
}
