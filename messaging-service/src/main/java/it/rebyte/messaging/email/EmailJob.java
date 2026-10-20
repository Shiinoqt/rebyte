package it.rebyte.messaging.email;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * One email to one recipient, carried as the {@code email.send} outbox payload.
 *
 * <p>{@code eventId} is derived from {@code (kind, dedupKey, recipientSub)}, so
 * the same logical email always has the same id and the unique outbox
 * {@code eventId} index rejects a second enqueue. {@code data} holds display
 * snippets such as a listing title and never message text. The recipient is
 * identified by sub; the address is resolved at send time.
 */
public record EmailJob(
        String eventId,
        EmailKind kind,
        String recipientSub,
        String dedupKey,
        Map<String, String> data,
        Instant occurredAt) {

    public static EmailJob of(EmailKind kind, String recipientSub, String dedupKey, Map<String, String> data) {
        String seed = "email|" + kind + "|" + dedupKey + "|" + recipientSub;
        String eventId = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
        return new EmailJob(eventId, kind, recipientSub, dedupKey, data, Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
