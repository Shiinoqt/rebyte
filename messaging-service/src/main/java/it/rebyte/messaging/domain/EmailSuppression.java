package it.rebyte.messaging.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One address that must not be mailed again, keyed by its lower-cased form.
 * It is written by an upsert in the suppression service, so redelivery of the
 * same SES event moves {@code lastSeenAt} and {@code count} but never
 * {@code firstSeenAt}.
 */
@Document(collection = "email_suppression")
public class EmailSuppression {

    public static final String BOUNCE_PERMANENT = "BOUNCE_PERMANENT";
    public static final String COMPLAINT = "COMPLAINT";

    @Id
    private final String address;
    private final String reason;
    private final String subType;
    private final Instant firstSeenAt;
    private final Instant lastSeenAt;
    private final String lastSesMessageId;
    private final long count;

    public EmailSuppression(String address, String reason, String subType, Instant firstSeenAt, Instant lastSeenAt,
            String lastSesMessageId, long count) {
        this.address = address;
        this.reason = reason;
        this.subType = subType;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.lastSesMessageId = lastSesMessageId;
        this.count = count;
    }

    public String getAddress() {
        return address;
    }

    public String getReason() {
        return reason;
    }

    public String getSubType() {
        return subType;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getLastSesMessageId() {
        return lastSesMessageId;
    }

    public long getCount() {
        return count;
    }
}
