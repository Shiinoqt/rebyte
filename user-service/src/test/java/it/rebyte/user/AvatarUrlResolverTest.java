package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import it.rebyte.user.mapper.AvatarUrlResolver;

/**
 * URL assembly for stored avatar keys (D-33, D-38).
 *
 * <p>{@code User.avatar} holds an object KEY, never a URL, so that the
 * stored value survives the distribution or domain change Phase 7 will
 * make. The public URL is therefore assembled at serialisation time from
 * one configured domain — this class is that single place.
 *
 * <p>Scope, decided 2026-09-11: the API-facing DTOs ({@code MeResponse},
 * {@code PublicProfileResponse}) carry the assembled URL, while the
 * {@code /internal} contract ({@code UserSummary}) keeps the bare key.
 * A consuming service that denormalises a summary — catalog projecting a
 * seller into Meilisearch from Phase 2 onward — would otherwise persist an
 * absolute URL and go stale on that same domain change, relocating exactly
 * the problem D-33 exists to prevent into another service's database.
 */
class AvatarUrlResolverTest {

    private static final String DOMAIN = "d37hy34ds2hhd9.cloudfront.net";

    private final AvatarUrlResolver resolver = new AvatarUrlResolver(DOMAIN);

    @Test
    void assemblesTheDistributionUrlFromAStoredKey() {
        assertThat(resolver.toPublicUrl("avatars/sub-1/0f1e2d3c"))
                .isEqualTo("https://" + DOMAIN + "/avatars/sub-1/0f1e2d3c");
    }

    @Test
    void aUserWithNoAvatarGetsNullAndNoDistributionRequest() {
        // D-38: null in the DTO, the client renders initials — never a
        // placeholder image URL, which would spend a CDN request per
        // avatarless render on an image identical for everyone.
        assertThat(resolver.toPublicUrl(null)).isNull();
    }

    @Test
    void aBlankStoredValueIsTreatedAsNoAvatar() {
        assertThat(resolver.toPublicUrl("")).isNull();
        assertThat(resolver.toPublicUrl("   ")).isNull();
    }

    @Test
    void theAssembledUrlNeverDoublesTheSeparator() {
        AvatarUrlResolver trailingSlash = new AvatarUrlResolver(DOMAIN + "/");

        assertThat(trailingSlash.toPublicUrl("avatars/sub-1/abc"))
                .isEqualTo("https://" + DOMAIN + "/avatars/sub-1/abc");
    }
}
