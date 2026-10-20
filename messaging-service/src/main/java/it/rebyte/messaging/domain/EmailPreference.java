package it.rebyte.messaging.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Global email switch for one user. The absence of a document means email is
 * enabled, so a user who never touched the setting needs no row.
 */
@Document(collection = "email_preference")
public class EmailPreference {

    @Id
    private final String userSub;
    private final boolean emailEnabled;
    private final Instant updatedAt;

    public EmailPreference(String userSub, boolean emailEnabled, Instant updatedAt) {
        this.userSub = userSub;
        this.emailEnabled = emailEnabled;
        this.updatedAt = updatedAt;
    }

    public String getUserSub() {
        return userSub;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
