package it.rebyte.gateway.config;

import java.net.URI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;

import reactor.core.publisher.Mono;

/**
 * Corpo RFC 9457 per gli errori del gateway (D-32).
 *
 * Le difese reattive di Spring Security rispondono di default 401/403 senza
 * corpo, e il filtro RequestRateLimiter risponde 429 altrettanto vuoto —
 * proprio i due errori che un client Angular gestisce piu spesso, quindi
 * resterebbero gli unici non parseable senza questa classe.
 * spring.webflux.problemdetails.enabled (application.yml) e la chiave
 * WebFlux, diversa da quella MVC gia usata in user-service.
 *
 * L'URI di tipo qui sotto e provvisorio: quando il piano 01-03 introduce
 * it.rebyte.commons.error.ProblemTypes (D-31), quei codici vanno allineati
 * alla stessa convenzione, cosi gateway e servizi emettono la stessa forma di
 * errore invece di due accreditate indipendentemente.
 */
@Configuration
public class ProblemDetailsConfig {

    private static final URI TYPE_UNAUTHENTICATED = URI.create("https://rebyte.it/problems/unauthenticated");
    private static final URI TYPE_ACCESS_DENIED = URI.create("https://rebyte.it/problems/access-denied");
    private static final URI TYPE_RATE_LIMITED = URI.create("https://rebyte.it/problems/rate-limited");

    private final ObjectMapper objectMapper;

    public ProblemDetailsConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 401: nessun token, o token non valido.
     *
     * Questo entry point e registrato in DUE punti di SecurityConfig — su
     * exceptionHandling e dentro la DSL oauth2ResourceServer — perche
     * quest'ultima installa altrimenti il proprio
     * BearerTokenServerAuthenticationEntryPoint, che ha la precedenza su ogni
     * fallimento di token. Con la sola prima registrazione il caso "token
     * scaduto o manomesso" tornava senza corpo.
     *
     * Sostituire l'entry point del resource server costa pero il challenge
     * RFC 6750, che quello di Spring impostava: va quindi riemesso qui, o un
     * 401 da risorsa bearer resta senza WWW-Authenticate. Non viene
     * reintrodotto error_description: il default di Spring ci esponeva
     * dettagli del decoder ("Invalid JWT serialization: Missing dot
     * delimiter(s)"), mentre T-02-04 limita le risposte d'errore a type,
     * title e status. L'informazione utile al client vive ora nel corpo.
     */
    @Bean
    ServerAuthenticationEntryPoint problemJsonAuthenticationEntryPoint() {
        return (exchange, ex) -> {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                    "Autenticazione richiesta");
            problem.setType(TYPE_UNAUTHENTICATED);
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge(ex));
            return writeProblemDetail(exchange, problem);
        };
    }

    /**
     * RFC 6750 §3: un tentativo di autenticazione FALLITO riporta il codice
     * d'errore, mentre una richiesta che non porta alcuna informazione di
     * autenticazione non dovrebbe riportarne uno — non c'e stato nessun
     * tentativo da descrivere.
     */
    private static String bearerChallenge(AuthenticationException ex) {
        if (ex instanceof OAuth2AuthenticationException oauth2Failure) {
            String errorCode = oauth2Failure.getError().getErrorCode();
            if (errorCode != null && !errorCode.isBlank()) {
                return "Bearer error=\"" + errorCode + "\"";
            }
        }
        return "Bearer";
    }

    /** 403: richiesta esclusa a prescindere dal token (es. denyAll su /internal/**, D-56). */
    @Bean
    ServerAccessDeniedHandler problemJsonAccessDeniedHandler() {
        return (exchange, ex) -> {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Accesso negato");
            problem.setType(TYPE_ACCESS_DENIED);
            return writeProblemDetail(exchange, problem);
        };
    }

    /**
     * 429: il filtro RequestRateLimiter di default non lancia nulla — imposta
     * lo stato e chiama setComplete(), chiudendo la risposta senza corpo.
     *
     * Qui c'era un gancio beforeCommit che provava a riscrivere quel corpo.
     * E stato misurato contro un Redis reale con il burst esaurito e NON
     * funziona: il gancio gira davvero, ed e per questo che il Content-Type
     * cambiava, ma writeWith() da dentro beforeCommit arriva a commit gia
     * iniziato, quindi la scrittura del corpo viene scartata mentre gli header
     * ancora mutabili restano. Il risultato era peggiore di un 429 vuoto e
     * onesto: l'header prometteva application/problem+json e il corpo era
     * lungo zero, cosi un client che smista sul Content-Type falliva sul
     * parse (D-32, 01-02-SUMMARY.md deviazione 1).
     *
     * throw-on-limit: true in application.yml fa sollevare al filtro una
     * HttpClientErrorException PRIMA del commit; da li il corpo si scrive
     * sulla normale via d'errore reattiva, nella stessa forma di 401 e 403.
     *
     * ATTENZIONE al tipo dichiarato: WebExceptionHandler, MAI
     * ErrorWebExceptionHandler. ErrorWebFluxAutoConfiguration registra il
     * proprio DefaultErrorWebExceptionHandler con
     * @ConditionalOnMissingBean(ErrorWebExceptionHandler.class, search = CURRENT):
     * dichiarare il tipo piu stretto lo farebbe arretrare, togliendo al
     * gateway la resa di default di ogni ALTRA eccezione. L'ordine -2 serve
     * solo a passare prima di quel bean, che e ordinato -1.
     */
    @Bean
    @Order(-2)
    WebExceptionHandler rateLimitProblemJsonExceptionHandler() {
        return (exchange, ex) -> {
            if (!(ex instanceof HttpClientErrorException httpError)
                    || httpError.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS
                    || exchange.getResponse().isCommitted()) {
                return Mono.error(ex);
            }
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                    "Troppe richieste, riprova piu tardi");
            problem.setType(TYPE_RATE_LIMITED);
            // Gli header X-RateLimit-* sono gia sulla risposta — il filtro li
            // aggiunge prima di sollevare — e restano l'unica meta di questo
            // errore che gia funzionava: non vanno ripuliti qui.
            return writeProblemDetail(exchange, problem);
        };
    }

    private Mono<Void> writeProblemDetail(ServerWebExchange exchange, ProblemDetail problemDetail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(problemDetail.getStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problemDetail);
        } catch (JsonProcessingException ex) {
            return Mono.error(ex);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
