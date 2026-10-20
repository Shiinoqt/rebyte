package it.rebyte.user.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import it.rebyte.user.domain.Admin;
import it.rebyte.user.repository.AdminRepository;

/**
 * Self-healing admin-row provisioning (D-08, D-09, D-10, IDENT-04). The row
 * is created just-in-time on the first authenticated request carrying
 * {@code ROLE_ADMIN} with no matching row — the identical mechanism as
 * {@link UserService#resolveOrProvision(Jwt)}, so the pattern is learned
 * once and applied twice (D-10).
 *
 * <p>It is self-healing with no operator step beyond the Cognito console
 * group add, including for the very first admin, which avoids a seed or
 * migration special case. Because the row is never an authorization
 * source, it is created once and <b>never deleted</b> — this class
 * deliberately has no delete/remove/revoke method (D-09): demotion leaves
 * the row in place, since {@code Moderation.admin} is a mandatory {@code
 * [1..1]} reference the binding model requires.
 *
 * <p>Authorization on any {@code ADMIN}-gated endpoint comes from the
 * {@code ROLE_ADMIN} authority the commons {@code
 * CognitoGroupsAuthoritiesConverter} produces from {@code cognito:groups},
 * checked via method security — never from the presence of this row
 * (T-03-01).
 */
@Service
public class AdminService {

    private final AdminRepository adminRepository;

    public AdminService(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    /**
     * Idempotent insert-on-primary-key-conflict — the same concurrency
     * pattern as {@link UserService#resolveOrProvision(Jwt)} and for the
     * same reason: NOT {@code @Transactional}, so a losing concurrent
     * insert's {@link DataIntegrityViolationException} cannot mark a
     * shared ambient transaction rollback-only before the recovery
     * re-read runs.
     */
    public Admin resolveOrProvision(Jwt jwt) {
        String sub = jwt.getSubject();
        return adminRepository.findById(sub).orElseGet(() -> provision(sub));
    }

    private Admin provision(String sub) {
        try {
            return adminRepository.saveAndFlush(new Admin(sub));
        } catch (DataIntegrityViolationException raceLost) {
            return adminRepository.findById(sub).orElseThrow(() -> raceLost);
        }
    }
}
