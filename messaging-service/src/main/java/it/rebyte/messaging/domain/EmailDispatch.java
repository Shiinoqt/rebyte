package it.rebyte.messaging.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailKind;

@Document(collection = "email_dispatch")
public record EmailDispatch(
        @Id String id,
        EmailKind kind,
        String recipientSub,
        String dedupKey,
        Status status,
        String ownerToken,
        Instant preparationStartedAt,
        Instant sendStartedAt,
        String sesMessageId,
        Instant createdAt,
        Instant updatedAt,
        @Indexed(name = "email_dispatch_expiry", expireAfter = "0s") Instant expiresAt) {

    public enum Status {
        PENDING, PREPARING, SENDING, SENT, SKIPPED_OPT_OUT, SKIPPED_READ, SKIPPED_THROTTLED,
        SKIPPED_NO_ADDRESS, SKIPPED_SUPPRESSED, FAILED_PERMANENT, DELIVERY_UNKNOWN;

        public boolean isTerminal() {
            return switch (this) {
                case SENT, SKIPPED_OPT_OUT, SKIPPED_READ, SKIPPED_THROTTLED, SKIPPED_NO_ADDRESS,
                        SKIPPED_SUPPRESSED, FAILED_PERMANENT -> true;
                default -> false;
            };
        }
    }

    public static String idFor(EmailJob job) {
        try {
            byte[] seed = (job.kind() + "|" + job.dedupKey() + "|" + job.recipientSub())
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }

    public static EmailDispatch pending(EmailJob job, Instant now) {
        return new EmailDispatch(idFor(job), job.kind(), job.recipientSub(), job.dedupKey(), Status.PENDING,
                null, null, null, null, now, now, null);
    }
}
