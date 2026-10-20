package it.rebyte.catalog.domain;
import java.io.Serializable;
public record FavouriteId(String userSub, String listingId) implements Serializable {}
