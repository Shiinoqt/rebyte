package it.rebyte.user.config;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import it.rebyte.user.service.AdminService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Self-healing admin-row JIT provisioning (D-10, IDENT-04): the identical
 * mechanism as {@code UserService#resolveOrProvision}, triggered on ANY
 * authenticated request carrying the {@code ROLE_ADMIN} authority the
 * commons {@code CognitoGroupsAuthoritiesConverter} produces from {@code
 * cognito:groups} — not scoped to a single endpoint, because group
 * membership can arrive on any authenticated call, and IDENT-04 does not
 * name a specific endpoint as the provisioning trigger.
 *
 * <p>Never an authorization source: this filter creates the row for
 * traceability and moderation back-references (D-08) but grants nothing —
 * the {@code ROLE_ADMIN} authority already present on the request (set by
 * Spring Security's JWT authentication BEFORE this filter runs) is what
 * authorization actually reads.
 */
public class AdminJitProvisioningFilter extends OncePerRequestFilter {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final AdminService adminService;

    public AdminJitProvisioningFilter(AdminService adminService) {
        this.adminService = adminService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt
                && hasAdminAuthority(authentication)) {
            adminService.resolveOrProvision(jwt);
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasAdminAuthority(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ADMIN_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
