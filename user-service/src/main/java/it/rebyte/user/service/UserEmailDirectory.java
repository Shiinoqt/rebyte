package it.rebyte.user.service;

import java.util.Optional;

import it.rebyte.user.dto.UserEmail;

/**
 * Resolves a user's email address, which lives only in the identity
 * provider. One implementation per auth profile: local-auth returns a
 * configured test inbox, cognito calls AdminGetUser.
 */
public interface UserEmailDirectory {

    /**
     * @return the address, or empty when none can be resolved for {@code sub}
     * @throws RuntimeException when the directory itself fails
     */
    Optional<UserEmail> resolve(String sub);
}
