package it.rebyte.catalog.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.dto.ListingCreatedResponse;
import it.rebyte.catalog.dto.PublishHardwareRequest;
import it.rebyte.catalog.service.ListingPublishService;
import it.rebyte.catalog.service.ListingReservationService;
import it.rebyte.catalog.dto.ListingDetailResponse;
import it.rebyte.catalog.dto.EditListingResponse;
import it.rebyte.catalog.dto.ChangePriceRequest;
import it.rebyte.catalog.service.PriceChangeService;
import it.rebyte.catalog.service.ListingEditService;
import it.rebyte.catalog.dto.EditListingRequest;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.access.prepost.PreAuthorize;
import it.rebyte.catalog.service.ListingRemovalService;
import it.rebyte.catalog.service.ListingQueryService;
import it.rebyte.catalog.service.ListingCardQueryService;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.ListingCardPage;

/**
 * {@code POST /listings/hardware} — the one entry point this plan wires.
 * The seller is always resolved from the authenticated JWT's {@code sub}
 * claim (T-02-01-01), never from the request body.
 */
@RestController
@RequestMapping("/listings")
public class ListingController {

    private final ListingPublishService listingPublishService;

    private final ListingReservationService listings;
    private final PriceChangeService priceChanges;
    private final ListingEditService editor;
    private final ListingRemovalService removal;
    private final ListingQueryService query;
    private final ListingCardQueryService cards;

    public ListingController(ListingPublishService listingPublishService, ListingReservationService listings,
            PriceChangeService priceChanges, ListingEditService editor, ListingRemovalService removal,
            ListingQueryService query, ListingCardQueryService cards) {
        this.listingPublishService = listingPublishService;
        this.listings = listings;
        this.priceChanges = priceChanges;
        this.editor = editor;
        this.removal = removal;
        this.query = query;
        this.cards = cards;
    }

    @GetMapping("/mine")
    public ListingCardPage mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) ListingStatus status,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return cards.mine(jwt.getSubject(), status, cursor, size);
    }

    @GetMapping("/{id}")
    public ListingDetailResponse detail(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return query.detail(id, jwt == null ? null : jwt.getSubject());
    }

    @PostMapping("/hardware")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingCreatedResponse publishHardware(
            @Valid @RequestBody PublishHardwareRequest request, @AuthenticationPrincipal Jwt jwt) {
        Listing listing = listingPublishService.publishHardware(request, jwt.getSubject());
        return ListingCreatedResponse.from(listing);
    }

    @PatchMapping("/{id}/price")
    public ListingCreatedResponse changePrice(@PathVariable String id, @Valid @RequestBody ChangePriceRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ListingCreatedResponse.from(priceChanges.changePrice(jwt.getSubject(), id, request.price()));
    }

    @PutMapping("/{id}")
    public EditListingResponse edit(@PathVariable String id, @Valid @RequestBody EditListingRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return EditListingResponse.from(editor.edit(jwt.getSubject(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or @listings.isOwner(#id, authentication.name)")
    public void remove(@PathVariable String id, @RequestParam(required=false) String reason,
            @AuthenticationPrincipal Jwt jwt) {
        boolean admin = jwt.getClaimAsStringList("cognito:groups") != null && jwt.getClaimAsStringList("cognito:groups").contains("ADMIN");
        removal.remove(jwt.getSubject(), admin, id, reason);
    }
}
