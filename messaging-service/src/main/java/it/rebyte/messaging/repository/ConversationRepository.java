package it.rebyte.messaging.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import it.rebyte.messaging.domain.Conversation;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Optional<Conversation> findByListingIdAndBuyerSub(String listingId, String buyerSub);
}
