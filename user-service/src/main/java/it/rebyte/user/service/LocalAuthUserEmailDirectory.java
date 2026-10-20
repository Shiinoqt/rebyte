package it.rebyte.user.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import it.rebyte.user.dto.UserEmail;

/**
 * Local-auth has no identity provider holding addresses, so every sub
 * resolves to one configured inbox. This keeps the live SES acceptance
 * pointed at a verified sandbox recipient.
 */
@Service
@Profile("local-auth")
public class LocalAuthUserEmailDirectory implements UserEmailDirectory {

    private final String address;

    public LocalAuthUserEmailDirectory(@Value("${rebyte.user.local-auth-email:}") String address) {
        this.address = address;
    }

    @Override
    public Optional<UserEmail> resolve(String sub) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new UserEmail(sub, address.trim(), true));
    }
}
