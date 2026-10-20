package it.rebyte.catalog.controller.internal;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import it.rebyte.catalog.dto.ListingAvailability;
import it.rebyte.catalog.dto.ReserveListingRequest;
import it.rebyte.catalog.dto.ReserveListingResponse;
import it.rebyte.catalog.service.ListingReservationService;

/** Trusted internal network only; the gateway denies this prefix and OpenAPI excludes this package. */
@RestController
@RequestMapping("/internal/listings")
public class CatalogInternalController {
    private final ListingReservationService service;
    public CatalogInternalController(ListingReservationService service) { this.service = service; }

    /** Reads Mongo directly, never Redis: a cached checkout price could charge the wrong amount. */
    @GetMapping("/{id}")
    public ListingAvailability availability(@PathVariable String id) { return service.availability(id); }

    @PatchMapping("/{id}/reserve")
    public ReserveListingResponse reserve(@PathVariable String id, @Valid @RequestBody ReserveListingRequest request) {
        return service.reserve(id, request.reservationId(), request.expectedVersion());
    }
}
