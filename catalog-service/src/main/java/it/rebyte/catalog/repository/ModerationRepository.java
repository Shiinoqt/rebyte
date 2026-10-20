package it.rebyte.catalog.repository;
import java.util.Optional; import org.springframework.data.mongodb.repository.MongoRepository; import it.rebyte.catalog.domain.Moderation;
public interface ModerationRepository extends MongoRepository<Moderation,String>{Optional<Moderation> findFirstByListingId(String listingId);}
