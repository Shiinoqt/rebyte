package it.rebyte.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import it.rebyte.catalog.controller.internal.InternalApi;
import it.rebyte.commons.security.CognitoGroupsAuthoritiesConverter;

/**
 * Resource server servlet/MVC di catalog-service, copiata dalla forma di
 * user-service (docs/rebyte_progettazione.md architecture.md §7: il gateway
 * autentica soltanto, ogni decisione di autorizzazione appartiene al
 * servizio che possiede la risorsa).
 *
 * <p>Un'unica catena attiva sotto entrambi i profili {@code cognito} e
 * {@code local-auth} — differiscono solo per {@code issuer-uri} nei
 * rispettivi {@code application-{profile}.yml}. {@code @EnableMethodSecurity}
 * e attivo da subito anche se nessun endpoint di questo piano lo richiede
 * ancora: ADMIN-01/ADMIN-02 (piano successivo) ne avranno bisogno, e
 * attivarlo ora evita di riaprire questa classe.
 *
 * <p>The internal prefix is permitted only for the dedicated controller package.
 */
@Configuration
@EnableMethodSecurity
@Import(CognitoGroupsAuthoritiesConverter.class)
public class SecurityConfig {

    private final CognitoGroupsAuthoritiesConverter groupsAuthoritiesConverter;

    public SecurityConfig(CognitoGroupsAuthoritiesConverter groupsAuthoritiesConverter) {
        this.groupsAuthoritiesConverter = groupsAuthoritiesConverter;
    }

    @Bean
    @Profile({"cognito", "local-auth"})
    SecurityFilterChain securedChain(HttpSecurity http) throws Exception {
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(groupsAuthoritiesConverter);

        http.csrf(CsrfConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    // l'healthcheck del container non ha un token da esibire
                    auth.requestMatchers("/actuator/health/**").permitAll();
                    // SRCH-01: la ricerca e pubblica — un visitatore non
                    // autenticato deve poter cercare senza account, esattamente
                    // come sfogliare l'albero delle categorie (CAT-07).
                    auth.requestMatchers("/search", "/search/**").permitAll();
                    // CAT-07 / D-01: l'albero delle categorie e lo schema
                    // effettivo di una categoria sono pubblici — un
                    // visitatore sfoglia il catalogo e il form di
                    // pubblicazione ha bisogno dello schema prima ancora che
                    // il venditore si autentichi. Solo GET: POST /categories
                    // resta ADMIN-gated dal @PreAuthorize del controller,
                    // sotto la regola di default piu in basso.
                    auth.requestMatchers(HttpMethod.GET, "/categories").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/categories/*/schema").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/categories/*/listings").permitAll();
                    // The gateway exposes GET /categories/** publicly; category detail must
                    // be anonymous here too. POST /categories stays behind anyRequest and ADMIN.
                    auth.requestMatchers(HttpMethod.GET, "/categories/*").permitAll();
                    auth.requestMatchers(InternalApi.PATH_PREFIX).permitAll();
                    // The owner's listing list shares the /listings/* shape, so it must be
                    // matched as authenticated before the public detail permit swallows it.
                    auth.requestMatchers(HttpMethod.GET, "/listings/mine").authenticated();
                    auth.requestMatchers(HttpMethod.GET, "/listings/*").permitAll();
                    // Seller profiles are public, and the gateway routes this path to catalog
                    // so user-service never has to read catalog data.
                    auth.requestMatchers(HttpMethod.GET, "/users/*/listings").permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
