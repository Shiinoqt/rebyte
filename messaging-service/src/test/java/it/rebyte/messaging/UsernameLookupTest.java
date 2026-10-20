package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.Map;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import it.rebyte.messaging.client.UserServiceClient;
import it.rebyte.messaging.client.UserSummary;
import it.rebyte.messaging.service.UsernameLookup;

class UsernameLookupTest {
    private final UserServiceClient users = mock(UserServiceClient.class);
    private final UsernameLookup lookup = new UsernameLookup(users);
    private final Request request = Request.create(Request.HttpMethod.GET, "http://users/internal/users/alice",
            Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);

    @Test
    void genuineNotFoundHasUnknownUsername() {
        when(users.getSummary("alice")).thenThrow(new FeignException.NotFound("missing", request, null, Map.of()));
        assertThat(lookup.username("alice")).isNull();
    }

    @Test
    void transientFailureCanBeRetriedToResolveUsername() {
        when(users.getSummary("alice")).thenThrow(new FeignException.ServiceUnavailable("unavailable", request, null, Map.of()))
                .thenReturn(new UserSummary("alice", "Alice"));
        assertThatThrownBy(() -> lookup.username("alice"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class)
                .hasCause(null).hasMessageNotContaining("private-recipient@example.test");
        assertThat(lookup.username("alice")).isEqualTo("Alice");
    }

    @Test
    void emptySuccessfulResponseMustBeRetried() {
        when(users.getSummary("alice")).thenReturn(null);
        assertThatThrownBy(() -> lookup.username("alice"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void successfulResponseWithoutAUsernameMustBeRetried(String username) {
        when(users.getSummary("alice")).thenReturn(new UserSummary("alice", username));
        assertThatThrownBy(() -> lookup.username("alice"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class);
    }

    @Test
    void conversationViewsCanStillUseBestEffortUsername() {
        when(users.getSummary("alice")).thenThrow(new IllegalStateException("timeout for private-recipient@example.test"));
        assertThat(lookup.bestEffortUsername("alice")).isNull();
    }

    @Test
    void timeoutPropagatesAsRetryableFailure() {
        when(users.getSummary("alice")).thenThrow(new IllegalStateException("timeout for private-recipient@example.test"));
        assertThatThrownBy(() -> lookup.username("alice"))
                .isInstanceOf(it.rebyte.messaging.error.UserServiceUnavailableException.class)
                .hasCause(null).hasMessageNotContaining("private-recipient@example.test");
    }
}
