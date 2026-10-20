package it.rebyte.messaging.config;

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

import it.rebyte.commons.security.CognitoGroupsAuthoritiesConverter;

/**
 * Resource server chain shared by the {@code cognito} and {@code local-auth}
 * profiles, which differ only in {@code issuer-uri}.
 *
 * <p>{@code /ws/**} is anonymous at the HTTP layer because a browser
 * WebSocket cannot send an {@code Authorization} header; the STOMP CONNECT
 * frame carries the token and is validated there. The unsubscribe path is
 * anonymous because its signed token is the credential.
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
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(groupsAuthoritiesConverter);
        return converter;
    }

    @Bean
    @Profile({"cognito", "local-auth"})
    SecurityFilterChain securedChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http.csrf(CsrfConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**").permitAll();
                    auth.requestMatchers("/ws/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/notifications/email-preferences/unsubscribe").permitAll();
                    auth.requestMatchers(HttpMethod.POST, "/notifications/email-preferences/unsubscribe").permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(
                        jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }
}
