package it.rebyte.catalog.dto;

import java.util.List;

public record FavouritePage(List<FavouriteEntry> favourites, int page, int size, long totalElements, int totalPages) { }
