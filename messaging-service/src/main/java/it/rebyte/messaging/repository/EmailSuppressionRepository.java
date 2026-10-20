package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.messaging.domain.EmailSuppression;

public interface EmailSuppressionRepository extends MongoRepository<EmailSuppression, String> {
}
