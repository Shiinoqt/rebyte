package it.rebyte.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import it.rebyte.commons.security.CognitoGroupsAuthoritiesConverter;

/**
 * Resource server servlet/MVC of payment-service, copied from
 * order-service's shape (docs/architecture.md §7: the gateway authenticates
 * only, every authorization decision belongs to the service that owns the
 * resource).
 *
 * <p>{@code POST /payments/{orderId}/capture} requires the buyer's JWT
 * (D-02: {@link it.rebyte.payment.service.PaymentService#capture} compares
 * the JWT subject against the immutable {@code buyerSub} stored at
 * creation, never a client-supplied identity). {@code /internal/**} is
 * deliberately unauthenticated — the gateway denies that prefix outright
 * and no external caller can reach it directly.
 *
 * <p>The public webhook is unauthenticated at the HTTP layer; its raw-body
 * signature is verified before event processing.
 */
@Configuration
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
                    auth.requestMatchers("/internal/**").permitAll();
                    auth.requestMatchers("/payments/webhook").permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
