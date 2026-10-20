package it.rebyte.catalog.repository;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.catalog.domain.Favourite;
import it.rebyte.catalog.domain.FavouriteId;

public interface FavouriteRepository extends MongoRepository<Favourite, FavouriteId> {
    List<Favourite> findByIdUserSub(String userSub);
    Page<Favourite> findByIdUserSub(String userSub, Pageable pageable);
    Optional<Favourite> findByIdUserSubAndIdListingId(String userSub, String listingId);
}
