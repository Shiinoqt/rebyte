package it.rebyte.messaging.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.messaging.domain.Notification;

public interface NotificationRepository extends MongoRepository<Notification, String> {
}
