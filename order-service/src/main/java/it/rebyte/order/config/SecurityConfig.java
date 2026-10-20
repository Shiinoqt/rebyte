package it.rebyte.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import it.rebyte.commons.security.CognitoGroupsAuthoritiesConverter;
import it.rebyte.order.controller.internal.InternalApi;

/**
 * Resource server servlet/MVC of order-service, copied from user-service's
 * and catalog-service's shape (docs/architecture.md §7: the gateway
 * authenticates only, every authorization decision belongs to the service
 * that owns the resource).
 *
 * <p>A single chain active under both {@code cognito} and {@code
 * local-auth} profiles — they differ only in {@code issuer-uri} in their
 * respective {@code application-{profile}.yml}.
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
                    // The container healthcheck has no token to present.
                    auth.requestMatchers("/actuator/health/**").permitAll();
                    // /internal/** is deliberately unauthenticated by design: the
                    // gateway denies the prefix outright and springdoc excludes it.
                    auth.requestMatchers(InternalApi.PATH_PREFIX).permitAll();
                    auth.requestMatchers("/admin/orders/**").hasRole("ADMIN");
                    auth.requestMatchers("/admin/problems/**").hasRole("ADMIN");
                    // Seller reviews are public, and the gateway routes only GET requests here.
                    auth.requestMatchers(HttpMethod.GET, "/users/*/reviews").permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
