package it.rebyte.order.support;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Shared {@code *IT} infrastructure for order-service (03-04-PLAN.md Task 1
 * and Task 2): a single reused real MySQL container (D-16's guard proof
 * needs real MySQL, not H2), Eureka/RabbitMQ silenced the same way {@code
 * user-service}'s {@code MySqlTestBase} does, a test-only {@link JwtDecoder}
 * (order-service declares no {@code nimbus-jose-jwt} dependency, mirroring
 * catalog-service's {@code TestJwtSupport}), and {@link CheckoutStub} — an
 * in-process HTTP stand-in for catalog-service's {@code /internal/listings}
 * and payment-service's {@code /internal/payments}, since payment-service
 * does not exist yet (03-05) and this plan's tracer/race proof is about the
 * MySQL guard and real Feign wiring, not the downstream services'
 * behaviour.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)}: same fix as {@code
 * user-service}'s {@code MySqlTestBase} — without it, a second {@code *IT}
 * class with byte-identical Spring context configuration can reuse the
 * FIRST class's cached context (and its {@code HikariDataSource}, whose
 * JDBC URL was fixed at container-creation time), while Testcontainers may
 * have already recycled the {@code @Container} field between classes.
 */
@Testcontainers
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        // Nothing in this plan's tests binds a queue or awaits an event;
        // this only stops the real dev RabbitMQ (reachable at the default
        // localhost:5672 per infra/docker-compose.yml) from being contacted
        // during context startup.
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "rebyte.outbox.relay.initial-delay-ms=3600000",
        "rebyte.fulfilment.sweep-initial-delay-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class OrderIntegrationTestBase {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    /**
     * local-auth-style bearer token: the {@code sub} claim only, decoded by
     * {@link StubJwtConfig}. No signing, no groups claim — the buyer-facing
     * checkout flow this plan tests never checks a Cognito group.
     */
    public static String token(String sub) {
        return sub;
    }

    /** Registers a test-only {@link JwtDecoder}, overriding Boot's issuer-uri-based auto-configuration entirely. */
    @TestConfiguration
    public static class StubJwtConfig {

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                Instant now = Instant.now();
                Map<String, Object> claims = token.startsWith("admin-")
                        ? Map.of("sub", token.substring(6), "cognito:groups", List.of("ADMIN"))
                        : token.startsWith("lower-admin-")
                        ? Map.of("sub", token.substring(12), "cognito:groups", List.of("admin"))
                        : Map.of("sub", token);
                return new Jwt(token, now, now.plusSeconds(300), Map.of("alg", "none"), claims);
            };
        }
    }

    /**
     * In-process stand-in for catalog-service's {@code GET
     * /internal/listings/{id}}, {@code PATCH /internal/listings/{id}/reserve}
     * and payment-service's {@code POST /internal/payments} (D-01, D-09,
     * D-13, D-17). Real MySQL and real Feign wiring drive every request;
     * only the two downstream services are stubbed, exactly as
     * 03-04-PLAN.md specifies for Task 1.
     */
    public static final class CheckoutStub {

        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();
        private final Map<String, Map<String, Object>> listings = new ConcurrentHashMap<>();
        private final List<Map<String, Object>> reserveCalls = Collections.synchronizedList(new ArrayList<>());
        private final List<Map<String, Object>> paymentCalls = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong paymentDelayMillis = new AtomicLong();
        private final AtomicBoolean paymentUnavailable = new AtomicBoolean();
        private final Map<String, String> reserveOutcomes = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> reserveFailures = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch> reserveHolds = new ConcurrentHashMap<>();
        private final Map<String, String> reserveWinners = new ConcurrentHashMap<>();
        private final java.util.Set<String> arbitratedListings = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> availabilityCalls = new ConcurrentHashMap<>();
        private final java.util.Set<String> failingAvailability = ConcurrentHashMap.newKeySet();
        private final Map<String, String> usernames = new ConcurrentHashMap<>();
        private final List<List<String>> userLookups = Collections.synchronizedList(new ArrayList<>());
        private final AtomicBoolean usersUnavailable = new AtomicBoolean();

        public CheckoutStub() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
                // A single-threaded default executor would serialize the N
                // concurrent buyers HardwareCheckoutRaceIT fires at the
                // catalog-availability endpoint before any MySQL guard is
                // even inserted, hiding the very race that test proves.
                server.setExecutor(Executors.newFixedThreadPool(32));
                server.createContext("/internal/listings/", this::handleListings);
                server.createContext("/internal/payments", this::handlePayment);
                server.createContext("/internal/users", this::handleUsers);
                server.start();
            } catch (IOException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        public int port() {
            return server.getAddress().getPort();
        }

        public void stop() {
            server.stop(0);
        }

        /** Registers the fresh catalog snapshot Task 1's checkout flow reads before validating and inserting the guard row. */
        public void registerListing(String id, String sellerSub, String listingTitle, BigDecimal price,
                BigDecimal shippingCost, String type, List<String> paymentMethods, boolean available, long version) {
            registerListing(id, sellerSub, listingTitle, price, shippingCost, type, paymentMethods, available,
                    version, null);
        }

        public void registerListing(String id, String sellerSub, String listingTitle, BigDecimal price,
                BigDecimal shippingCost, String type, List<String> paymentMethods, boolean available, long version,
                String licenceKeyPattern) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("sellerSub", sellerSub);
            body.put("listingTitle", listingTitle);
            body.put("price", price);
            body.put("shippingCost", shippingCost);
            body.put("type", type);
            body.put("paymentMethods", paymentMethods);
            body.put("available", available);
            body.put("version", version);
            body.put("licenceKeyPattern", licenceKeyPattern);
            listings.put(id, body);
        }

        /** Registers the display name {@code GET /internal/users?subs=} answers for this sub; unregistered subs are omitted. */
        public void registerUser(String sub, String username) {
            usernames.put(sub, username);
        }

        /** Every {@code GET /internal/users} this stub received, as the list of subs asked for, in arrival order. */
        public List<List<String>> userLookups() {
            synchronized (userLookups) {
                return List.copyOf(userLookups);
            }
        }

        public int userCalls() {
            return userLookups.size();
        }

        /** While set, {@code GET /internal/users} answers HTTP 503. */
        public void setUsersUnavailable(boolean unavailable) {
            usersUnavailable.set(unavailable);
        }

        public void removeListing(String id) {
            listings.remove(id);
        }

        /** Every {@code PATCH .../reserve} this stub received, in arrival order — the assertion surface for D-17/D-19. */
        public List<Map<String, Object>> reserveCalls() {
            synchronized (reserveCalls) {
                return List.copyOf(reserveCalls);
            }
        }

        /** Every {@code POST /internal/payments} this stub received. */
        public List<Map<String, Object>> paymentCalls() {
            synchronized (paymentCalls) {
                return List.copyOf(paymentCalls);
            }
        }

        public void setPaymentDelayMillis(long delayMillis) {
            paymentDelayMillis.set(delayMillis);
        }

        public void setPaymentUnavailable(boolean unavailable) {
            paymentUnavailable.set(unavailable);
        }

        public void setReserveOutcome(String listingId, String outcome) {
            reserveOutcomes.put(listingId, outcome);
        }

        public void failReserve(String listingId, int times) {
            reserveFailures.put(listingId, new AtomicInteger(times));
        }

        public void holdReserve(String listingId, CountDownLatch release) {
            reserveHolds.put(listingId, release);
        }

        public void arbitrateReserve(String listingId) {
            arbitratedListings.add(listingId);
        }

        /** Every {@code GET /internal/listings/{id}} this stub received for this listing, since the stub was created. */
        public int availabilityCalls(String listingId) {
            AtomicInteger count = availabilityCalls.get(listingId);
            return count == null ? 0 : count.get();
        }

        /** While set, a {@code GET /internal/listings/{id}} for this listing answers with HTTP 500 instead of the snapshot. */
        public void failAvailability(String listingId, boolean fail) {
            if (fail) {
                failingAvailability.add(listingId);
            } else {
                failingAvailability.remove(listingId);
            }
        }

        @SuppressWarnings("unchecked")
        private void handleListings(HttpExchange exchange) throws IOException {
            try {
                String[] segments = exchange.getRequestURI().getPath().split("/");
                // "", "internal", "listings", "{id}"[, "reserve"]
                String id = segments.length > 3 ? segments[3] : null;
                boolean isReserve = segments.length == 5 && "reserve".equals(segments[4]);

                if (isReserve && "PATCH".equalsIgnoreCase(exchange.getRequestMethod())) {
                    Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), Map.class);
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("listingId", id);
                    call.put("reservationId", request.get("reservationId"));
                    call.put("expectedVersion", request.get("expectedVersion"));
                    reserveCalls.add(call);

                    CountDownLatch release = reserveHolds.get(id);
                    if (release != null) {
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                exchange.sendResponseHeaders(503, -1);
                                exchange.close();
                                return;
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            exchange.sendResponseHeaders(503, -1);
                            exchange.close();
                            return;
                        }
                    }
                    AtomicInteger failures = reserveFailures.get(id);
                    if (failures != null && failures.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }

                    Map<String, Object> response = new LinkedHashMap<>();
                    String outcome = reserveOutcomes.getOrDefault(id, "RESERVED");
                    if (arbitratedListings.contains(id)) {
                        String winner = reserveWinners.putIfAbsent(id, String.valueOf(request.get("reservationId")));
                        outcome = winner == null || winner.equals(request.get("reservationId"))
                                ? "RESERVED" : "ALREADY_TAKEN";
                    }
                    response.put("outcome", outcome);
                    response.put("reservationId", "RESERVED".equals(outcome) ? request.get("reservationId") : null);
                    writeJson(exchange, 200, response);
                    return;
                }

                if (id != null && segments.length == 4 && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    availabilityCalls.computeIfAbsent(id, key -> new AtomicInteger()).incrementAndGet();
                    if (failingAvailability.contains(id)) {
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }
                    Map<String, Object> listing = listings.get(id);
                    if (listing == null) {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    writeJson(exchange, 200, listing);
                    return;
                }

                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        }

        @SuppressWarnings("unchecked")
        private void handlePayment(HttpExchange exchange) throws IOException {
            try {
                if (paymentUnavailable.get()) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                // The stub keeps no payment rows, so every status lookup is payment-service's 404.
                if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), Map.class);
                paymentCalls.add(request);
                long delay = paymentDelayMillis.get();
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        exchange.sendResponseHeaders(503, -1);
                        exchange.close();
                        return;
                    }
                }
                String orderId = String.valueOf(request.get("orderId"));

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("paymentId", "stub-payment-" + orderId);
                response.put("approvalUrl", "https://paypal.sandbox.example/approve/" + orderId);
                writeJson(exchange, 200, response);
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        }

        private void handleUsers(HttpExchange exchange) throws IOException {
            try {
                List<String> subs = new ArrayList<>();
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null) {
                    for (String pair : query.split("&")) {
                        String[] keyValue = pair.split("=", 2);
                        if (keyValue.length == 2 && "subs".equals(keyValue[0])) {
                            String value = java.net.URLDecoder.decode(keyValue[1], java.nio.charset.StandardCharsets.UTF_8);
                            for (String sub : value.split(",")) {
                                subs.add(sub);
                            }
                        }
                    }
                }
                userLookups.add(List.copyOf(subs));
                if (usersUnavailable.get()) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                List<Map<String, Object>> body = new ArrayList<>();
                for (String sub : subs) {
                    String username = usernames.get(sub);
                    if (username != null) {
                        Map<String, Object> summary = new LinkedHashMap<>();
                        summary.put("sub", sub);
                        summary.put("username", username);
                        summary.put("avatar", null);
                        summary.put("isDeveloper", false);
                        summary.put("ratingAvg", null);
                        summary.put("ratingCount", 0);
                        summary.put("completedSales", 0);
                        body.add(summary);
                    }
                }
                writeJson(exchange, 200, body);
            } catch (RuntimeException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        }

        private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
