package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.messaging.domain.FavouriteProjection;

public interface FavouriteProjectionRepository extends MongoRepository<FavouriteProjection, String> {
}
