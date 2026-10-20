package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.support.MySqlTestBase;

/**
 * DOC-01, D-69: the generated OpenAPI document lists the public API and
 * never the {@code /internal/**} surface — asserted structurally (a
 * throwaway controller placed inside {@code
 * it.rebyte.user.controller.internal} for the duration of this test),
 * under concurrency (two simultaneous requests must agree), and never
 * vacuously (the document must contain at least one public path).
 *
 * <p>The {@code openapi-exclusion-throwaway} profile activates {@link
 * it.rebyte.user.controller.internal.ThrowawayInternalController}, a
 * test-only controller living in the exact package every real internal
 * controller uses — proving the exclusion in {@code application.yml}
 * (D-56, D-69) is a package rule that cannot be forgotten on a new
 * internal endpoint, not a per-endpoint list someone has to remember to
 * update.
 *
 * <p>Swagger UI's own disablement under the deployed profile
 * ({@code application-cognito.yml}) is not exercised here — it needs a
 * real Cognito issuer this environment does not have — and is instead
 * asserted by the plan's own grep-based {@code <verify>} step.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"local-auth", "openapi-exclusion-throwaway"})
class OpenApiInternalExclusionIT extends MySqlTestBase {

    private static final String API_DOCS_PATH = "/v3/api-docs";

    @DynamicPropertySource
    static void issuerMatchesReservedPort(DynamicPropertyRegistry registry) {
        int port = findFreePort();
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:" + port + "/");
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private String bearerToken() {
        return LocalAuthTokens.mint(restTemplate, UUID.randomUUID().toString(), List.of());
    }

    private ResponseEntity<Map> requestDocument() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearerToken());
        return restTemplate.exchange(API_DOCS_PATH, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @Test
    void documentListsPublicPathsAndNoInternalPath() {
        ResponseEntity<Map> response = requestDocument();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        Map<String, Object> paths = (Map<String, Object>) body.get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths).isNotEmpty();
        assertThat(paths.keySet()).anyMatch(path -> !path.startsWith("/internal"));
        assertThat(paths.keySet()).noneMatch(path -> path.startsWith("/internal"));
    }

    @Test
    void throwawayControllerInsideInternalPackageStaysAbsentFromTheDocument() {
        // ThrowawayInternalController is active for this whole test class
        // (openapi-exclusion-throwaway profile) — its mapping is
        // /internal/throwaway-test-controller. If the exclusion were a
        // per-path or per-endpoint list rather than a package rule, this
        // new controller would leak into the document with zero changes
        // to application.yml.
        Map<String, Object> paths = (Map<String, Object>) requestDocument().getBody().get("paths");

        assertThat(paths.keySet()).noneMatch(path -> path.startsWith("/internal"));
        assertThat(paths).doesNotContainKey("/internal/throwaway-test-controller");
    }

    @Test
    void twoConcurrentRequestsReturnTheIdenticalPathSetWithNoInternalPath() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            Future<Set<String>> first = executor.submit(() -> {
                startLine.await();
                return ((Map<String, Object>) requestDocument().getBody().get("paths")).keySet();
            });
            Future<Set<String>> second = executor.submit(() -> {
                startLine.await();
                return ((Map<String, Object>) requestDocument().getBody().get("paths")).keySet();
            });

            startLine.countDown();

            Set<String> firstPaths = first.get(10, TimeUnit.SECONDS);
            Set<String> secondPaths = second.get(10, TimeUnit.SECONDS);

            assertThat(firstPaths).isNotEmpty();
            assertThat(firstPaths).isEqualTo(secondPaths);
            assertThat(firstPaths).noneMatch(path -> path.startsWith("/internal"));
            assertThat(secondPaths).noneMatch(path -> path.startsWith("/internal"));
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError("concurrent document requests failed", e);
        } finally {
            executor.shutdownNow();
        }
    }
}
