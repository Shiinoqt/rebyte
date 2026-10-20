package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import it.rebyte.user.dto.UserEmail;
import it.rebyte.user.service.CognitoUserEmailDirectory;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

class CognitoUserEmailDirectoryTest {

    private static final String POOL = "eu-south-1_TESTPOOL";

    private final CognitoIdentityProviderClient client = mock(CognitoIdentityProviderClient.class);
    private final CognitoUserEmailDirectory directory = new CognitoUserEmailDirectory(client, POOL);

    private static AttributeType attr(String name, String value) {
        return AttributeType.builder().name(name).value(value).build();
    }

    private void respondWith(AttributeType... attributes) {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenReturn(AdminGetUserResponse.builder().userAttributes(attributes).build());
    }

    @Test
    void requestCarriesPoolIdAndSubAsUsername() {
        respondWith(attr("email", "a@example.test"), attr("email_verified", "true"));

        directory.resolve("sub-1");

        ArgumentCaptor<AdminGetUserRequest> captor = ArgumentCaptor.forClass(AdminGetUserRequest.class);
        verify(client).adminGetUser(captor.capture());
        assertThat(captor.getValue().userPoolId()).isEqualTo(POOL);
        assertThat(captor.getValue().username()).isEqualTo("sub-1");
    }

    @Test
    void verifiedEmailMapsToVerifiedTrue() {
        respondWith(attr("sub", "sub-1"), attr("email", "a@example.test"), attr("email_verified", "true"));

        Optional<UserEmail> result = directory.resolve("sub-1");

        assertThat(result).contains(new UserEmail("sub-1", "a@example.test", true));
    }

    @Test
    void unverifiedEmailMapsToVerifiedFalse() {
        respondWith(attr("email", "a@example.test"), attr("email_verified", "false"));

        Optional<UserEmail> result = directory.resolve("sub-1");

        assertThat(result).contains(new UserEmail("sub-1", "a@example.test", false));
    }

    @Test
    void missingEmailAttributeMapsToEmpty() {
        respondWith(attr("sub", "sub-1"), attr("email_verified", "true"));

        assertThat(directory.resolve("sub-1")).isEmpty();
    }

    @Test
    void userNotFoundMapsToEmpty() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(UserNotFoundException.builder().message("missing").build());

        assertThat(directory.resolve("sub-1")).isEmpty();
    }

    @Test
    void otherCognitoFailuresPropagate() {
        when(client.adminGetUser(any(AdminGetUserRequest.class)))
                .thenThrow(CognitoIdentityProviderException.builder().message("boom").build());

        assertThatThrownBy(() -> directory.resolve("sub-1"))
                .isInstanceOf(CognitoIdentityProviderException.class);
    }
}
