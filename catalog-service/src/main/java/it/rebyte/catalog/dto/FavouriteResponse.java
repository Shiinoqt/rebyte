package it.rebyte.catalog.dto;
import java.time.Instant; import it.rebyte.catalog.domain.Favourite;
public record FavouriteResponse(String listingId,String sellerSub,Instant createdAt){public static FavouriteResponse from(Favourite f){return new FavouriteResponse(f.getId().listingId(),f.getSellerSub(),f.getCreatedAt());}}
