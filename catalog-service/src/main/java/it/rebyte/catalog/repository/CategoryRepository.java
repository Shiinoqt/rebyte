package it.rebyte.catalog.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.catalog.domain.Category;

public interface CategoryRepository extends MongoRepository<Category, String> {
}
