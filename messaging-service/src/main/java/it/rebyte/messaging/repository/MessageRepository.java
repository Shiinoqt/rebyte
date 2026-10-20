package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import it.rebyte.messaging.domain.Message;

public interface MessageRepository extends MongoRepository<Message, String> {

    @Query(value = "{'imageKeys': ?0}", exists = true)
    boolean existsByImageKeys(String key);
}
