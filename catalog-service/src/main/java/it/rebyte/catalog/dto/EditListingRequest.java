package it.rebyte.catalog.dto;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
public record EditListingRequest(@NotBlank String title, @NotBlank String description,
        List<String> photos, List<AttributeValueInput> attributes, String categoryId, String licenceKeyPattern) {
    public EditListingRequest { if (photos == null) photos = List.of(); if (attributes == null) attributes = List.of(); }
}
