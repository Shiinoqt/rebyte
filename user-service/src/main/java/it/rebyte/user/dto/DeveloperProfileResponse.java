package it.rebyte.user.dto;

import java.time.Instant;

/**
 * {@code POST /users/me/developer-profile} response (USER-05). Idempotent:
 * a repeat call returns the same {@code activatedAt}.
 */
public record DeveloperProfileResponse(String sub, Instant activatedAt) {
}
