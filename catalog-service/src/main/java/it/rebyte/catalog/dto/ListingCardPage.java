package it.rebyte.catalog.dto;

import java.util.List;

public record ListingCardPage(List<ListingCard> items, String nextCursor) {
}
