package it.rebyte.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import it.rebyte.commons.security.CognitoGroupsAuthoritiesConverter;
import it.rebyte.user.controller.internal.InternalApi;
import it.rebyte.user.service.AdminService;

/**
 * Resource server servlet/MVC di user-service (D-12, D-55).
 *
 * <p>Un'unica catena attiva sotto entrambi i profili {@code cognito} e
 * {@code local-auth} — differiscono solo per {@code issuer-uri} nei rispettivi
 * {@code application-{profile}.yml}, esattamente come gia nota il commento del
 * gateway. A differenza del gateway, qui non esiste un profilo {@code dev} da
 * cancellare: user-service non ne ha mai avuto uno.
 *
 * <p>{@link CognitoGroupsAuthoritiesConverter} viene importato esplicitamente
 * con {@code @Import} (D-12: e una libreria, non uno starter — cio che e
 * attivo deve restare visibile leggendo questa classe) e collegato qui,
 * il punto in cui IDENT-03 diventa reale per il primo servizio di business.
 *
 * <p>{@code @EnableMethodSecurity}: nessun endpoint di questo piano lo
 * richiede, ma il piano 01-03 ne ha bisogno per gli endpoint gated su
 * {@code ADMIN} — attivarlo qui evita di riaprire questa classe.
 *
 * <p>docs/architecture.md §7: il gateway autentica e basta, ogni decisione di
 * autorizzazione appartiene al servizio che possiede la risorsa — questo e
 * quel servizio.
 */
@Configuration
@EnableMethodSecurity
@Import(CognitoGroupsAuthoritiesConverter.class)
public class SecurityConfig {

    private final CognitoGroupsAuthoritiesConverter groupsAuthoritiesConverter;
    private final AdminService adminService;

    public SecurityConfig(CognitoGroupsAuthoritiesConverter groupsAuthoritiesConverter, AdminService adminService) {
        this.groupsAuthoritiesConverter = groupsAuthoritiesConverter;
        this.adminService = adminService;
    }

    @Bean
    @Profile({"cognito", "local-auth"})
    SecurityFilterChain securedChain(HttpSecurity http, Environment environment) throws Exception {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(groupsAuthoritiesConverter);

        http.csrf(CsrfConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    // l'healthcheck del container non ha un token da esibire
                    auth.requestMatchers("/actuator/health/**").permitAll();
                    // /internal/** e deliberatamente non autenticato per
                    // design (D-52, D-56, T-05-02, T-05-03): il gateway lo
                    // nega con una rotta esplicita (piano 01-02) e springdoc
                    // lo esclude (D-69), ma qui, all'interno del servizio,
                    // nessun controller in it.rebyte.user.controller.internal
                    // esegue mai una scrittura — il pacchetto e proprio cio
                    // che rende questa assenza di autenticazione visibile in
                    // revisione, non un'omissione.
                    auth.requestMatchers(InternalApi.PATH_PREFIX).permitAll();
                    // il minter e la sua JWKS sono deliberatamente senza
                    // credenziali (D-26, D-29) — ma esistono SOLO come bean
                    // sotto @Profile("local-auth") (LocalAuthKeyConfig,
                    // LocalAuthTokenController), quindi permetterli qui sotto
                    // "cognito" non apre comunque nessuna route reale.
                    if (environment.matchesProfiles("local-auth")) {
                        auth.requestMatchers(
                                "/.well-known/jwks.json",
                                "/.well-known/openid-configuration",
                                "/local-auth/token").permitAll();
                    }
                    // Owner endpoints dereference the JWT, so they must match before the public wildcard.
                    auth.requestMatchers("/users/me", "/users/me/**").authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/users/*").permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // D-10, IDENT-04: runs after JWT authentication has already
                // populated the SecurityContext, so it can read the
                // ROLE_ADMIN authority the converter above just produced.
                .addFilterAfter(new AdminJitProvisioningFilter(adminService), BearerTokenAuthenticationFilter.class);

        return http.build();
    }
}
