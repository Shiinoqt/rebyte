package it.rebyte.catalog.dto;

import java.util.List;

import it.rebyte.catalog.service.ListingEditService.EditResult;

/** Result of an edit, including values removed by a category move. */
public record EditListingResponse(ListingCreatedResponse listing, List<String> droppedAttributeIds) {

    public static EditListingResponse from(EditResult result) {
        return new EditListingResponse(ListingCreatedResponse.from(result.listing()), result.droppedAttributeIds());
    }
}
