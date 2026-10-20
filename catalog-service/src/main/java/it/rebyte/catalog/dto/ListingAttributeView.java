package it.rebyte.catalog.dto;

import it.rebyte.catalog.domain.AttributeType;

public record ListingAttributeView(String name, AttributeType type, Object value, String unit) {}
