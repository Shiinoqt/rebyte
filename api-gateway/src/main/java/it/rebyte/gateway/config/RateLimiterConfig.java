package it.rebyte.gateway.config;

import java.security.Principal;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.adapter.ForwardedHeaderTransformer;

import reactor.core.publisher.Mono;

/**
 * Chiave del token bucket (docs/architecture.md §6).
 *
 * Il RedisRateLimiter e configurato via property in application.yml: qui serve
 * solo dire su cosa contare.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    @Profile("demo")
    ForwardedHeaderTransformer forwardedHeaderTransformer(
            @Value("${spring.cloud.gateway.server.webflux.trusted-proxies:}") String trustedProxies,
            @Value("${server.forward-headers-strategy:NONE}") String strategy) {
        if (trustedProxies.isBlank()) {
            throw new IllegalStateException("spring.cloud.gateway.server.webflux.trusted-proxies");
        }
        if (!"FRAMEWORK".equalsIgnoreCase(strategy)) {
            throw new IllegalStateException("server.forward-headers-strategy");
        }
        Pattern trusted = Pattern.compile(trustedProxies);
        var removeOnly = new ForwardedHeaderTransformer();
        removeOnly.setRemoveOnly(true);
        return new ForwardedHeaderTransformer() {
            @Override
            public ServerHttpRequest apply(ServerHttpRequest request) {
                var peer = request.getRemoteAddress();
                if (peer == null || peer.getAddress() == null
                        || !trusted.matcher(peer.getAddress().getHostAddress()).matches()) {
                    return removeOnly.apply(request);
                }
                // Traefik sanitizes X-Forwarded metadata but passes the client's RFC Forwarded header through.
                return super.apply(request.mutate().headers(headers -> {
                    headers.remove("Forwarded");
                    headers.remove("X-Forwarded-Prefix");
                    headers.remove("X-Forwarded-Ssl");
                }).build());
            }
        };
    }

    /**
     * Per-IP e il default per le rotte protette: non richiede un principal
     * autenticato, ma dietro NAT punisce piu utenti insieme. @Primary perche
     * RequestRateLimiterGatewayFilterFactory inietta un solo KeyResolver: con
     * due bean candidati e nessun default esplicito il contesto non parte
     * (NoUniqueBeanDefinitionException).
     */
    @Bean
    @Primary
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(addr -> addr.getAddress() == null ? addr.getHostString() : addr.getAddress().getHostAddress())
                        .orElse("unknown"));
    }

    /**
     * Chiave per-utente per le rotte dove una decisione impone un limite
     * per-sub (D-15), come POST /offers: usa il sub del JWT gia validato, piu
     * equo del per-IP quando piu utenti condividono un NAT. Mono vuoto se non
     * c'e un principal autenticato, cosi il deny-empty-key di default rifiuta
     * la richiesta invece di farla condividere una chiave "anonima".
     */
    @Bean
    KeyResolver subKeyResolver() {
        return exchange -> exchange.<Principal>getPrincipal().map(Principal::getName);
    }
}
