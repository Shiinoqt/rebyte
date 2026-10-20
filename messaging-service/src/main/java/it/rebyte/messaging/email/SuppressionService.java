package it.rebyte.messaging.email;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.EmailSuppression;
import it.rebyte.messaging.repository.EmailSuppressionRepository;

/**
 * Local copy of the addresses SES reported as undeliverable or as a complaint.
 * The address is never logged here or by callers: it is personal data.
 */
@Service
public class SuppressionService {

    private final EmailSuppressionRepository suppressions;
    private final MongoTemplate mongoTemplate;

    public SuppressionService(EmailSuppressionRepository suppressions, MongoTemplate mongoTemplate) {
        this.suppressions = suppressions;
        this.mongoTemplate = mongoTemplate;
    }

    public boolean isSuppressed(String address) {
        return suppressions.existsById(normalize(address));
    }

    /**
     * Single upsert so a redelivered SES event stays one record. Mongo stores
     * dates in milliseconds, so the stamp is truncated up front.
     */
    public void record(String address, String reason, String subType, String sesMessageId, Instant at) {
        Instant stamp = at.truncatedTo(ChronoUnit.MILLIS);
        Update update = new Update()
                .setOnInsert("firstSeenAt", stamp)
                .setOnInsert("reason", reason)
                .max("lastSeenAt", stamp)
                .set("subType", subType)
                .set("lastSesMessageId", sesMessageId)
                .inc("count", 1);
        mongoTemplate.upsert(Query.query(Criteria.where("_id").is(normalize(address))), update, EmailSuppression.class);
    }

    private static String normalize(String address) {
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
