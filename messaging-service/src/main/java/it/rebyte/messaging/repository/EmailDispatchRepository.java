package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import it.rebyte.messaging.domain.EmailDispatch;

public interface EmailDispatchRepository extends MongoRepository<EmailDispatch, String> {
}
