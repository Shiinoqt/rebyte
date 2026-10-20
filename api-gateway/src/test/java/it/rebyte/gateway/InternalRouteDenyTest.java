package it.rebyte.gateway;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * D-56: /internal/** e negato dal gateway — mai proxato verso un servizio a
 * valle — indipendentemente dall'ordine delle rotte nel file di
 * configurazione.
 *
 * Il chiamante del primo test possiede un token valido (mockJwt): e proprio
 * il caso che conta, perche SecurityConfig non ha una regola dedicata per
 * /internal/** e lascia che anyExchange().authenticated() lo tratti come
 * qualunque altra rotta — un chiamante senza token prende gia 401 prima di
 * arrivare al gateway di routing. Il deny reale — 403, mai proxato, anche
 * per chi ha gia superato l'autenticazione — e compito esclusivo della
 * rotta ordinata per prima in application.yml (SetStatus=403); il secondo
 * test verifica esattamente quell'ordine nella tabella delle rotte risolte,
 * cosi un riordino futuro fa fallire il test invece di riesporre l'API
 * interna in silenzio.
 *
 * bindToApplicationContext (non un server reale su porta casuale) e cio che
 * permette a mockJwt() di iniettare un'autenticazione di prova nel
 * SecurityContext reattivo, senza un IdP vero.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
class InternalRouteDenyTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionLocator routeDefinitions;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    void nestedInternalPathIsDeniedNotProxiedForAnAuthenticatedCaller() {
        webTestClient
                .mutateWith(mockJwt())
                .get()
                .uri("/internal/users/some-sub/followers")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void denyRouteIsFirstInResolvedRouteTable() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes).isNotEmpty();
        assertThat(routes.get(0).getId()).isEqualTo("internal-deny");
    }

    @Test
    void chatWriteRouteIncludesUploadPoliciesWithThePerUserLimiter() {
        List<RouteDefinition> routes = routeDefinitions.getRouteDefinitions().collectList().block();
        RouteDefinition chatWrite = routes.stream()
                .filter(route -> "messaging-chat-write".equals(route.getId())).findFirst().orElseThrow();
        RouteDefinition messaging = routes.stream()
                .filter(route -> "messaging-service".equals(route.getId())).findFirst().orElseThrow();

        assertThat(chatWrite.getOrder()).isLessThan(messaging.getOrder());
        assertThat(chatWrite.getPredicates()).filteredOn(predicate -> "Path".equals(predicate.getName()))
                .singleElement().satisfies(predicate -> assertThat(predicate.getArgs().values())
                        .containsExactly("/conversations", "/conversations/*/messages", "/messages/image-upload-policy"));
        assertThat(chatWrite.getPredicates()).filteredOn(predicate -> "Method".equals(predicate.getName()))
                .singleElement().satisfies(predicate -> assertThat(predicate.getArgs().values()).containsExactly("POST"));
        assertThat(chatWrite.getFilters()).filteredOn(filter -> "RequestRateLimiter".equals(filter.getName()))
                .singleElement().satisfies(filter -> assertThat(filter.getArgs())
                        .containsEntry("key-resolver", "#{@subKeyResolver}")
                        .containsEntry("throw-on-limit", "true"));
    }

    /**
     * SecurityConfig#securedChain ora nega /internal/** anche a livello di
     * catena di sicurezza (denyAll), quindi il test sopra —
     * nestedInternalPathIsDeniedNotProxiedForAnAuthenticatedCaller —
     * resterebbe verde anche se internal-deny scivolasse dietro una rotta di
     * servizio in application.yml: il denyAll lo coprirebbe comunque. Questo
     * test pin la garanzia a livello di rotta indipendentemente da quella
     * seconda difesa, leggendo la tabella delle rotte risolte e asserendo
     * che internal-deny precede ciascuna rotta di servizio per nome, cosi un
     * riordino in application.yml fa fallire questo assert anche quando la
     * catena di sicurezza maschera il sintomo a livello HTTP.
     */
    @Test
    void denyRoutePrecedesEveryNamedServiceRoute() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        assertThat(routes).isNotEmpty();

        int denyIndex = indexOfRoute(routes, "internal-deny");
        assertThat(denyIndex).as("internal-deny must be present in the resolved route table").isNotEqualTo(-1);

        List<String> serviceRouteIds = List.of(
                "user-service", "catalog-service", "messaging-service", "order-service", "payment-service");
        for (String serviceRouteId : serviceRouteIds) {
            int serviceIndex = indexOfRoute(routes, serviceRouteId);
            assertThat(serviceIndex)
                    .as("%s must be present in the resolved route table", serviceRouteId)
                    .isNotEqualTo(-1);
            assertThat(denyIndex)
                    .as("internal-deny must precede %s", serviceRouteId)
                    .isLessThan(serviceIndex);
        }
    }

    private static int indexOfRoute(List<Route> routes, String routeId) {
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).getId().equals(routeId)) {
                return i;
            }
        }
        return -1;
    }
}
