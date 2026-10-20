package it.rebyte.user.dto;

import java.time.Instant;

/**
 * Risposta di {@code GET /users/me} (USER-01). {@code avatar} e {@code null}
 * per un utente senza avatar — il client renderizza le iniziali (D-38).
 */
public record MeResponse(
        String id,
        String username,
        Instant registration,
        String location,
        String bio,
        String avatar) {
}
