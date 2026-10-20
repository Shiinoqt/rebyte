package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.messaging.client.FollowersPage;
import it.rebyte.messaging.client.UserEmail;
import it.rebyte.messaging.service.UsernameLookup;
import it.rebyte.messaging.support.MessagingTestBase;
import it.rebyte.messaging.support.StubHttpServer;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = "spring.cloud.openfeign.client.config.user-service.readTimeout=500")
class UsernameLookupIT extends MessagingTestBase {

    private static final StubHttpServer SERVER = new StubHttpServer();

    @DynamicPropertySource
    static void userServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", SERVER::baseUrl);
    }

    @Autowired
    UsernameLookup usernames;

    @Autowired
    it.rebyte.messaging.client.UserServiceClient users;

    @BeforeEach
    void clearRecordedRequests() {
        SERVER.clearRequests();
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Test
    void usernameReturnsTheSummaryNameAndRetriesServerFailure() {
        SERVER.respond("/internal/users/alice", 200, "{\"sub\":\"alice\",\"username\":\"Alice\"}");
        SERVER.respond("/internal/users/missing", 500, "{}");

        assertThat(usernames.username("alice")).isEqualTo("Alice");
        assertThatThrownBy(() -> usernames.username("missing"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
    }

    @Test
    void usernameRetriesWhenTheServiceTimesOut() {
        SERVER.respond("/internal/users/slow", 200, "{\"sub\":\"slow\",\"username\":\"Slow\"}", 1_000);

        assertThatThrownBy(() -> usernames.username("slow"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
    }

    @Test
    void genuineNotFoundReturnsUnknownUsername() {
        SERVER.respond("/internal/users/deleted", 404, "{}");
        assertThat(usernames.username("deleted")).isNull();
    }

    @Test
    void successfulResponseWithoutUsernameMustBeRetried() {
        SERVER.respond("/internal/users/empty", 204, "");
        SERVER.respond("/internal/users/incomplete", 200, "{\"sub\":\"incomplete\"}");
        assertThatThrownBy(() -> usernames.username("empty"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
        assertThatThrownBy(() -> usernames.username("incomplete"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
    }

    @Test
    void usernamesResolveTheDistinctBatchWithOneRequest() {
        SERVER.respond("/internal/users", 200,
                "[{\"sub\":\"alice\",\"username\":\"Alice\"},"
                        + "{\"sub\":\"bob\",\"username\":\"Bob\"},"
                        + "{\"sub\":\"carol\",\"username\":\"Carol\"}]");

        Map<String, String> result = usernames.usernames(List.of("alice", "bob", "alice", " ", "carol"));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                "alice", "Alice", "bob", "Bob", "carol", "Carol"));
        assertThat(SERVER.requests()).hasSize(1);
        StubHttpServer.Request request = SERVER.requests().getFirst();
        assertThat(request.path()).isEqualTo("/internal/users");
        String query = URLDecoder.decode(request.query(), StandardCharsets.UTF_8);
        assertThat(query).contains("subs=alice", "subs=bob", "subs=carol");
    }

    @Test
    void usernamesRejectMoreThanFiftyDistinctNonBlankSubs() {
        List<String> subs = IntStream.range(0, 51).mapToObj(i -> "sub-" + i).toList();

        assertThatThrownBy(() -> usernames.usernames(subs)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SERVER.requests()).isEmpty();
    }

    @Test
    void followersMapsThePageAndPassesCursorAndLimit() {
        SERVER.respond("/internal/users/seller/followers", 200,
                "{\"subs\":[\"follower-1\",\"follower-2\"],\"nextCursor\":\"follower-2\"}");

        FollowersPage page = users.followers("seller", "cursor-1", 25);

        assertThat(page.subs()).containsExactly("follower-1", "follower-2");
        assertThat(page.nextCursor()).isEqualTo("follower-2");
        assertThat(SERVER.requests()).singleElement().satisfies(request -> {
            assertThat(request.path()).isEqualTo("/internal/users/seller/followers");
            assertThat(URLDecoder.decode(request.query(), StandardCharsets.UTF_8))
                    .contains("cursor=cursor-1", "limit=25");
        });
    }

    @Test
    void emailMapsTheAddressAndVerificationFlag() {
        SERVER.respond("/internal/users/buyer/email", 200,
                "{\"sub\":\"buyer\",\"email\":\"buyer@example.test\",\"verified\":true}");

        UserEmail email = users.email("buyer");

        assertThat(email).isEqualTo(new UserEmail("buyer", "buyer@example.test", true));
    }
}
