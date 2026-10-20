package it.rebyte.user.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import it.rebyte.user.domain.DeveloperProfile;
import it.rebyte.user.repository.DeveloperProfileRepository;

/**
 * Developer-profile activation (USER-05, D-55). Owner-scoped: the caller
 * always supplies its own {@code sub} (never a path variable), so there is
 * no URL that activates a developer profile on someone else's account.
 * Activation is idempotent — a repeat call returns the existing activation
 * rather than an error — because a retried request must not surface an
 * error for an operation that already succeeded.
 */
@Service
public class DeveloperProfileService {

    private final DeveloperProfileRepository developerProfileRepository;

    public DeveloperProfileService(DeveloperProfileRepository developerProfileRepository) {
        this.developerProfileRepository = developerProfileRepository;
    }

    public DeveloperProfile activate(String sub) {
        return developerProfileRepository.findById(sub).orElseGet(() -> provision(sub));
    }

    private DeveloperProfile provision(String sub) {
        // Truncated to microseconds like UserService#provision: DATETIME(6)
        // stores microsecond precision, and Instant.now()'s nanosecond
        // precision would otherwise make the freshly-activated response
        // differ from the same row re-read on a later idempotent call.
        Instant activatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            return developerProfileRepository.saveAndFlush(new DeveloperProfile(sub, activatedAt));
        } catch (DataIntegrityViolationException raceLost) {
            return developerProfileRepository.findById(sub).orElseThrow(() -> raceLost);
        }
    }

    /** Used by {@link UserProfileService#getPublicProfile(String, String)} for {@code isDeveloper}. */
    public boolean isActivated(String sub) {
        return developerProfileRepository.existsById(sub);
    }
}
