package it.rebyte.catalog.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.catalog.domain.Listing;

public interface ListingRepository extends MongoRepository<Listing, String> {
}
