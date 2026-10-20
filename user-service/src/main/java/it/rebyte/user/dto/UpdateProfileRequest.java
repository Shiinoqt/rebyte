package it.rebyte.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code PUT /users/me} body (D-57). Deliberately carries only the mutable
 * fields — {@code username}, {@code location}, {@code bio} — so a
 * mass-assignment attempt against {@code id}, {@code registration} or
 * {@code avatar} has nothing to bind to (T-03-02).
 *
 * <p>PUT semantics: all three fields are replaced together on every call —
 * {@code location}/{@code bio} may be {@code null} to clear them, but are
 * never left unchanged by omission (there is no partial-PATCH merge here).
 */
public record UpdateProfileRequest(
        @NotBlank @Size(min = 3, max = 30) String username,
        @Size(max = 80) String location,
        @Size(max = 500) String bio) {
}
