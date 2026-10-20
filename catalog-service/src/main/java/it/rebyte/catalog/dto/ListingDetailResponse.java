package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.SoftwareListing;

/** Public buyer view of a listing. The seller summary is resolved live on each request. */
public record ListingDetailResponse(
        String id,
        String title,
        String description,
        BigDecimal price,
        ListingStatus status,
        ListingType type,
        Instant publication,
        String sellerSub,
        String categoryId,
        List<String> photos,
        List<String> photoKeys,
        Set<PaymentMethod> paymentMethods,
        Condition condition,
        String shippingLocation,
        BigDecimal shippingCost,
        SoftwareListingView software,
        ListingCategoryView category,
        List<PriceChangeResponse> priceHistory,
        List<ListingAttributeView> attributes,
        SellerSummaryView seller,
        @JsonInclude(JsonInclude.Include.NON_NULL) ModerationView moderation) {

    public static ListingDetailResponse from(Listing listing, ListingCategoryView category,
            List<ListingAttributeView> attributes, List<PriceChangeResponse> priceHistory, SellerSummaryView seller,
            List<String> photos) {
        return from(listing, category, attributes, priceHistory, seller, photos, null);
    }

    public static ListingDetailResponse from(Listing listing, ListingCategoryView category,
            List<ListingAttributeView> attributes, List<PriceChangeResponse> priceHistory, SellerSummaryView seller,
            List<String> photos, ModerationView moderation) {
        List<String> photoKeys = List.copyOf(listing.getPhotos());
        HardwareListing hardware = listing.getHardware();
        SoftwareListing software = listing.getSoftware();
        return new ListingDetailResponse(
                listing.getId(), listing.getTitle(), listing.getDescription(), listing.getPrice(), listing.getStatus(),
                listing.type(), listing.getPublication(), listing.getSellerSub(), listing.getCategoryId(),
                List.copyOf(photos), photoKeys, Set.copyOf(listing.getPaymentMethods()),
                hardware == null ? null : hardware.getCondition(),
                hardware == null ? null : hardware.getShippingLocation(),
                hardware == null ? null : hardware.getShippingCost(),
                software == null ? null : new SoftwareListingView(
                        software.getPlatforms(),
                        software.getVersions().stream()
                                .map(version -> VersionResponse.from(version,
                                        version.getNumber().equals(software.currentVersion().getNumber())))
                                .toList()),
                category, List.copyOf(priceHistory), List.copyOf(attributes), seller, moderation);
    }
}
