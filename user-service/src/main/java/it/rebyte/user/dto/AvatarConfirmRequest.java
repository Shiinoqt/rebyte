package it.rebyte.user.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /users/me/avatar/confirm} body (USER-03, D-35) — the key the client just uploaded. */
public record AvatarConfirmRequest(@NotBlank String key) {
}
