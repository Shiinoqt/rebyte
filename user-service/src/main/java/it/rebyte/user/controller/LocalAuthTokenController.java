package it.rebyte.user.controller;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Minter {@code local-auth} (D-26, D-27, D-29).
 *
 * <p>Attivo solo sotto {@code @Profile("local-auth")} — insieme a
 * {@link it.rebyte.user.config.LocalAuthKeyConfig}, questo e cio che rende
 * strutturalmente impossibile che il minter esista in una build
 * {@code cognito}.
 *
 * <p>I claim del token emesso rispecchiano deliberatamente la forma di un
 * access token Cognito, incluso il prefisso vendor {@code cognito:} sul
 * claim dei gruppi (D-27): un nome neutro come {@code groups} costringerebbe
 * {@link it.rebyte.commons.security.CognitoGroupsAuthoritiesConverter} a
 * ramificare per profilo, e la mappatura reale di IDENT-03 non sarebbe mai
 * esercitata in locale. Nessuna verifica di credenziali: il chiamante
 * specifica {@code sub} e {@code groups} a piacere (D-29) — questo endpoint
 * non e mai raggiungibile fuori da un ambiente di sviluppo.
 *
 * <p>Espone anche {@code /.well-known/openid-configuration}: Spring Security
 * risolve {@code issuer-uri} tramite discovery (OIDC, RFC 8414, o metadata
 * OAuth2 — {@code NimbusJwtDecoder.withIssuerLocation}), non chiamando
 * direttamente {@code /.well-known/jwks.json}. Senza questo documento minimo
 * {@code issuer-uri} nella configurazione del gateway e di questo stesso
 * servizio non risolverebbe mai la JWKS, e ogni validazione fallirebbe.
 */
@RestController
@Profile("local-auth")
public class LocalAuthTokenController {

    private static final String GROUPS_CLAIM = "cognito:groups";
    private static final long TOKEN_TTL_SECONDS = 3600;

    private final RSAKey rsaKey;
    private final String issuer;

    public LocalAuthTokenController(
            RSAKey rsaKey,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.rsaKey = rsaKey;
        this.issuer = issuer;
    }

    /** Solo la chiave pubblica esce da qui — {@code toPublicJWK()} rimuove l'esponente privato. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return Map.of("keys", List.of(rsaKey.toPublicJWK().toJSONObject()));
    }

    /**
     * Documento di discovery minimo: solo {@code issuer} e {@code jwks_uri},
     * il sottoinsieme che {@code NimbusJwtDecoder.withIssuerLocation} legge
     * per risolvere la JWKS a partire dal solo {@code issuer-uri}.
     */
    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> openIdConfiguration() {
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        return Map.of(
                "issuer", issuer,
                "jwks_uri", base + "/.well-known/jwks.json");
    }

    @PostMapping("/local-auth/token")
    public MintTokenResponse mint(@RequestBody MintTokenRequest request) throws JOSEException {
        List<String> groups = Optional.ofNullable(request.groups()).orElseGet(List::of);
        Instant now = Instant.now();

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(request.sub())
                .claim(GROUPS_CLAIM, groups)
                .claim("token_use", "access")
                .issuer(issuer)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
                .build();

        SignedJWT signedJwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claims);
        signedJwt.sign(new RSASSASigner(rsaKey));

        return new MintTokenResponse(signedJwt.serialize());
    }

    public record MintTokenRequest(String sub, List<String> groups) {
    }

    public record MintTokenResponse(String accessToken) {
    }
}
