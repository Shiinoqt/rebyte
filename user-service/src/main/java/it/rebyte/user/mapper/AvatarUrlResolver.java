package it.rebyte.user.mapper;

import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Assembles the public avatar URL from a stored object key (D-33, D-38).
 *
 * <p>{@code User.avatar} stores the KEY, never a URL, so the stored value
 * survives the distribution or domain change Phase 7 will make with no
 * data migration. The URL is therefore assembled at serialisation time,
 * and this class is the single place the distribution domain is read —
 * {@code rebyte.media.distribution-domain} has no default in
 * {@code application.yml} by design, so a deployment missing it refuses to
 * start rather than silently serving broken image links.
 *
 * <p><b>Scope (decided 2026-09-11).</b> The API-facing DTOs
 * ({@code MeResponse}, {@code PublicProfileResponse}) carry the assembled
 * URL; the {@code /internal} contract ({@code UserSummary}) deliberately
 * keeps the bare key. From Phase 2 onward catalog-service denormalises a
 * seller summary into its Meilisearch projection — handing it an absolute
 * URL would persist the CDN domain inside another service's read model and
 * make those documents stale on the Phase 7 domain change, relocating the
 * exact problem D-33 exists to prevent instead of solving it. A key does
 * not age.
 */
@Component
public class AvatarUrlResolver {

    private final String distributionDomain;

    public AvatarUrlResolver(@Value("${rebyte.media.distribution-domain}") String distributionDomain) {
        // Tolerate a configured value with a trailing slash: the domain is
        // supplied by an operator through MEDIA_DISTRIBUTION_DOMAIN, and a
        // doubled separator would produce a URL that 404s on CloudFront.
        this.distributionDomain = distributionDomain.replaceAll("/+$", "");
    }

    /**
     * @param key the stored object key, or {@code null} for a user with no avatar
     * @return the distribution URL, or {@code null} so the client renders initials (D-38)
     */
    @Named("avatarKeyToUrl")
    public String toPublicUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return "https://" + distributionDomain + "/" + key;
    }
}
