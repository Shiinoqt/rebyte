package it.rebyte.user.service;

import org.springframework.stereotype.Service;

import it.rebyte.user.dto.UsernameAvailabilityResponse;
import it.rebyte.user.error.InvalidUsernameException;
import it.rebyte.user.repository.UserRepository;

/**
 * Advisory username check for the sign-up form (D-28). It applies the same
 * rules as {@code PUT /users/me} but neither reserves the name nor creates
 * a user row: the unique index behind the rename is the only authority.
 */
@Service
public class UsernameAvailabilityService {

    private final UsernameRules usernameRules;
    private final UserRepository userRepository;

    public UsernameAvailabilityService(UsernameRules usernameRules, UserRepository userRepository) {
        this.usernameRules = usernameRules;
        this.userRepository = userRepository;
    }

    public UsernameAvailabilityResponse check(String raw) {
        String normalized;
        try {
            normalized = usernameRules.normalizeAndValidate(raw);
        } catch (InvalidUsernameException invalid) {
            return new UsernameAvailabilityResponse(false, "invalid-username");
        }
        if (usernameRules.isReservedShape(normalized)) {
            return new UsernameAvailabilityResponse(false, "reserved-handle");
        }
        if (userRepository.existsByUsername(normalized)) {
            return new UsernameAvailabilityResponse(false, "username-taken");
        }
        return new UsernameAvailabilityResponse(true, null);
    }
}
