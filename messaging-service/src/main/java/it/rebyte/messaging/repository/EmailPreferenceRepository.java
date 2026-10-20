package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.messaging.domain.EmailPreference;

public interface EmailPreferenceRepository extends MongoRepository<EmailPreference, String> {
}
