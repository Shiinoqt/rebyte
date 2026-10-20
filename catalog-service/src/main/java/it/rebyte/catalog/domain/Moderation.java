package it.rebyte.catalog.domain;
import java.time.Instant; import org.springframework.data.annotation.Id; import org.springframework.data.mongodb.core.mapping.Document;
@Document(collection="moderation") public class Moderation { @Id private final String id; private final String listingId; private final String adminSub; private final Instant instant; private final String reason;
 public Moderation(String id,String listingId,String adminSub,Instant instant,String reason){this.id=id;this.listingId=listingId;this.adminSub=adminSub;this.instant=instant;this.reason=reason;} public String getId(){return id;} public String getListingId(){return listingId;} public String getAdminSub(){return adminSub;} public Instant getInstant(){return instant;} public String getReason(){return reason;}}
