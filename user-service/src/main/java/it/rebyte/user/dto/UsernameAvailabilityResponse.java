package it.rebyte.user.dto;

/**
 * {@code GET /users/username-available} (D-28). {@code reason} is {@code
 * null} when the name is free, otherwise one of {@code invalid-username},
 * {@code reserved-handle} or {@code username-taken}. Nothing else about
 * the holder of a taken name is ever returned.
 */
public record UsernameAvailabilityResponse(boolean available, String reason) {
}
