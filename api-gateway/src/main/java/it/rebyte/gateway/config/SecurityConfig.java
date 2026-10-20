package it.rebyte.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

/**
 * Un'unica catena di sicurezza, attiva sia in cognito sia in local-auth
 * (docs/architecture.md §2c). Il blocco resource-server e identico nei due
 * profili: cambia solo il valore di issuer-uri.
 *
 * local-auth resta solo per lo sviluppo offline e non viene mai deployato: un
 * vero fallback di produzione richiederebbe verificare credenziali — password
 * hash in user_db — cosa che D-02 vieta esplicitamente. La conseguenza
 * accettata e nessun fallback se Cognito e irraggiungibile in produzione
 * (D-29).
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * §7: il gateway autentica e basta. Nessun controllo di ruolo qui — ogni
     * decisione di autorizzazione appartiene al servizio che possiede la
     * risorsa, l'unico che sa se il chiamante e il proprietario, un admin, o
     * nessuno dei due.
     *
     * /internal/** ha qui una regola dedicata, ordinata prima di
     * anyExchange().authenticated(): denyAll() nega l'accesso anche a un
     * chiamante gia autenticato, che altrimenti supererebbe
     * anyExchange().authenticated() come qualunque altra rotta business e
     * raggiungerebbe il gateway di routing. Questa e la correzione a D-56:
     * la proprieta "mai proxato verso un servizio a valle, a prescindere
     * dall'ordine delle rotte" non puo dipendere dalla sola posizione della
     * rotta internal-deny in application.yml, perche quella posizione e un
     * dato di configurazione che un riordino futuro puo silenziosamente
     * invertire. denyAll() qui la rende invece una proprieta della catena di
     * sicurezza, verificata a ogni richiesta indipendentemente da come sono
     * ordinate le rotte.
     *
     * La rotta ordinata per prima in application.yml (SetStatus=403, senza
     * proxy) resta comunque — le due difese sono difesa in profondita
     * deliberata, non la stessa proprieta verificata due volte: un riordino
     * delle rotte lascia comunque denyAll() a negare qui; un bug futuro in
     * questa catena lascia comunque la rotta a negare senza mai instradare
     * la richiesta a valle. Un chiamante anonimo prende comunque 401
     * dall'authenticationEntryPoint — anyExchange().authenticated() lo
     * respinge prima che denyAll() sia anche solo valutato, esattamente come
     * per ogni altra rotta business; denyAll() si applica solo a chi ha gia
     * superato quel controllo, dove Spring Security reattivo lo traduce
     * correttamente in 403 (accessDeniedHandler), non 401.
     * InternalRouteDenyTest verifica entrambe le proprieta: il 403 per un
     * chiamante autenticato, e la posizione della rotta nella tabella
     * risolta — cosi un riordino di application.yml fa fallire quel secondo
     * assert anche se il primo resta verde grazie a denyAll() qui.
     */
    @Bean
    @Profile({"cognito", "local-auth"})
    SecurityWebFilterChain securedChain(
            ServerHttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            ServerAuthenticationEntryPoint problemJsonAuthenticationEntryPoint,
            ServerAccessDeniedHandler problemJsonAccessDeniedHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // Preflights carry no credentials, so CORS must run before authentication.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(ex -> ex
                        // l'healthcheck del container non ha un token da esibire
                        .pathMatchers("/actuator/health/**").permitAll()
                        // D-56: deny indipendente dall'ordine delle rotte in application.yml
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers(HttpMethod.POST, "/payments/webhook").permitAll()
                        // il browser non puo inviare Authorization su una WebSocket: il token viaggia nel frame STOMP CONNECT
                        .pathMatchers("/ws/**").permitAll()
                        // il token firmato nella query e la credenziale: i client di posta aprono il link senza sessione
                        .pathMatchers(HttpMethod.GET, "/notifications/email-preferences/unsubscribe").permitAll()
                        .pathMatchers(HttpMethod.POST, "/notifications/email-preferences/unsubscribe").permitAll()
                        // Owner paths precede wildcards that also match me and mine; services retain authorization.
                        .pathMatchers("/users/me", "/users/me/**", "/listings/mine").authenticated()
                        .pathMatchers(HttpMethod.GET, "/search", "/search/**", "/categories", "/categories/**",
                                "/listings/*", "/users/*", "/users/*/reviews", "/users/*/listings").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemJsonAuthenticationEntryPoint)
                        .accessDeniedHandler(problemJsonAccessDeniedHandler))
                // L'entry point va passato ANCHE qui, non solo a
                // exceptionHandling: oauth2ResourceServer installa il proprio
                // BearerTokenServerAuthenticationEntryPoint, che ha la
                // precedenza su ogni fallimento di token. Senza questa riga
                // solo il caso "nessuna credenziale" arrivava all'entry point
                // problem+json, mentre un token scaduto o manomesso — il 401
                // di gran lunga piu frequente per un client reale — tornava
                // vuoto con il solo header WWW-Authenticate. Il javadoc di
                // problemJsonAuthenticationEntryPoint dichiarava di coprire
                // "nessun token, o token non valido" da subito: questa riga e
                // cio che rende vera la seconda meta. Pinnato da
                // ProblemDetailErrorBodyTest#invalidTokenReturns401WithProblemJsonBody.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problemJsonAuthenticationEntryPoint)
                        .jwt(jwt -> {}))
                .build();
    }
}
