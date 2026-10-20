package it.rebyte.commons.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Copre IDENT-03 / D-12: mappatura di {@code cognito:groups} su authority
 * Spring Security, inclusi i casi limite di adiacenza (duplicati), assenza
 * (empty) e ordine (ordering) richiesti dal must-have del piano 01-01.
 */
class GroupsToAuthoritiesConverterTest {

    private final CognitoGroupsAuthoritiesConverter converter = new CognitoGroupsAuthoritiesConverter();

    @Test
    void twoGroupHappyPath() {
        Jwt jwt = jwtWithGroupsClaim(List.of("ADMIN", "SELLER"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_SELLER");
    }

    @Test
    void duplicateGroupInputCollapsesToOneAuthority() {
        Jwt jwt = jwtWithGroupsClaim(List.of("ADMIN", "ADMIN"));

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void missingClaimYieldsEmptyCollection() {
        Jwt jwt = jwtWithClaims(Map.of());

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void nullClaimValueYieldsEmptyCollection() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("cognito:groups", null);
        Jwt jwt = jwtWithClaims(claims);

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void emptyListYieldsEmptyCollection() {
        Jwt jwt = jwtWithGroupsClaim(List.of());

        Collection<GrantedAuthority> authorities = converter.convert(jwt);

        assertThat(authorities).isEmpty();
    }

    @Test
    void authorityMappingIsOrderInsensitive() {
        Jwt jwtAdminFirst = jwtWithGroupsClaim(List.of("ADMIN", "USER"));
        Jwt jwtUserFirst = jwtWithGroupsClaim(List.of("USER", "ADMIN"));

        Collection<GrantedAuthority> fromAdminFirst = converter.convert(jwtAdminFirst);
        Collection<GrantedAuthority> fromUserFirst = converter.convert(jwtUserFirst);

        assertThat(fromAdminFirst).isEqualTo(fromUserFirst);
    }

    private static Jwt jwtWithGroupsClaim(List<String> groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("cognito:groups", groups);
        return jwtWithClaims(claims);
    }

    private static Jwt jwtWithClaims(Map<String, Object> extraClaims) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        claims.putIfAbsent("sub", "test-sub");
        Jwt.Builder builder = Jwt.withTokenValue("token-value")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
