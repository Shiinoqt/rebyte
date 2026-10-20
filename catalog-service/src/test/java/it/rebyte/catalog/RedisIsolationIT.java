package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.support.MongoTestBase;

/**
 * Proves the 2026-09-23 folded todo (D-39): catalog {@code *IT} never
 * connects to the developer's own dev Redis — the exact corruption that
 * happened running {@code PublishHardwareIT} against the live {@code
 * rebyte:catalog:category-tree} key. {@code application-test.yml} poisons
 * {@code spring.data.redis.host}/{@code port} to an address nothing
 * listens on (127.0.0.1:1); {@link MongoTestBase}'s {@code
 * @ServiceConnection(name = "redis")} container must still make every
 * Redis operation succeed, proving its connection-details replacement
 * wins regardless of the configured property value — not merely that it
 * happens to differ from {@code localhost:6379} by coincidence.
 *
 * <p>No web environment and no {@code local-auth} profile needed — this
 * proves context wiring and cache round-tripping, not an HTTP contract
 * (mirrors {@code MongoDecimal128IT}'s bare {@code @SpringBootTest} with no
 * active profile, which leaves {@code SecurityConfig}'s
 * {@code @Profile({"cognito","local-auth"})}-gated filter chain — and its
 * {@code JwtDecoder} requirement — simply absent).
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisIsolationIT extends MongoTestBase {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private CategoryTreeCache categoryTreeCache;

    @Value("${spring.data.redis.host}")
    private String configuredRedisHost;

    @Value("${spring.data.redis.port}")
    private int configuredRedisPort;

    @Test
    void resolvedRedisAddressDiffersFromTheConfiguredDevAddress() {
        // The configured property is the 127.0.0.1:1 canary from
        // application-test.yml — nothing ever listens there. The actual
        // container the app is really talking to is a different, real,
        // dynamically-mapped address.
        assertThat(configuredRedisHost).isEqualTo("127.0.0.1");
        assertThat(configuredRedisPort).isEqualTo(1);

        String actualHost = REDIS.getHost();
        int actualPort = REDIS.getMappedPort(6379);

        assertThat(actualPort)
                .as("the live connection's port must differ from the configured (unreachable) dev address")
                .isNotEqualTo(configuredRedisPort);
        assertThat(actualHost + ":" + actualPort)
                .as("the live connection's address must differ from the configured (unreachable) dev address")
                .isNotEqualTo(configuredRedisHost + ":" + configuredRedisPort);
    }

    @Test
    void categoryTreeCacheRoundTripsThroughTheIsolatedContainerWithDevRedisAddressUnreachable() {
        // CategoryRootBootstrap has already seeded the fixed Hardware/Software
        // roots by the time any *IT context is up (see CategoryAdminIT).
        categoryTreeCache.evict();

        List<CategoryTreeCache.CachedCategory> tree = categoryTreeCache.getTree();

        assertThat(tree).isNotEmpty();
        // A direct read through the SAME RedisTemplate/connection factory the
        // application uses proves the round trip went through the isolated
        // container, not the poisoned 127.0.0.1:1 canary (which would have
        // thrown a connection-refused failure well before this assertion).
        assertThat(redisTemplate.opsForValue().get("rebyte:catalog:category-tree")).isNotNull();
    }
}
