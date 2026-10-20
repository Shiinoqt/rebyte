package it.rebyte.user.dto;

/**
 * {@code GET /internal/users/{sub}/email}. The address is resolved on demand
 * from the identity provider and is never persisted or logged by
 * user-service.
 */
public record UserEmail(String sub, String email, boolean verified) {
}
