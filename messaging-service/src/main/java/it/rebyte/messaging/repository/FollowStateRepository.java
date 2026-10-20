package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.messaging.domain.FollowState;

public interface FollowStateRepository extends MongoRepository<FollowState, String> {
}
