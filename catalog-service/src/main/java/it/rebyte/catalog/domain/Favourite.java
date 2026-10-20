package it.rebyte.catalog.domain;
import java.time.Instant; import org.springframework.data.annotation.Id; import org.springframework.data.mongodb.core.mapping.Document;
@Document(collection="favourite") public class Favourite {
 @Id private final FavouriteId id; private final Instant createdAt; private final String sellerSub;
 public Favourite(FavouriteId id, Instant createdAt, String sellerSub){this.id=id;this.createdAt=createdAt;this.sellerSub=sellerSub;}
 public FavouriteId getId(){return id;} public Instant getCreatedAt(){return createdAt;} public String getSellerSub(){return sellerSub;}
}
