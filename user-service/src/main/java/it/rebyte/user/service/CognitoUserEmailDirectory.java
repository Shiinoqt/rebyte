package it.rebyte.user.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import it.rebyte.user.dto.UserEmail;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

/**
 * Resolves the address through Cognito AdminGetUser. The sub is used as the
 * username, which Cognito accepts as long as it is not also an alias. Any SDK
 * failure other than "user not found" propagates so the caller answers 503.
 * Neither the address nor the attribute list is ever logged.
 */
@Service
@Profile("cognito")
public class CognitoUserEmailDirectory implements UserEmailDirectory {

    private static final String EMAIL = "email";
    private static final String EMAIL_VERIFIED = "email_verified";

    private final CognitoIdentityProviderClient client;
    private final String userPoolId;

    public CognitoUserEmailDirectory(
            CognitoIdentityProviderClient client,
            @Value("${rebyte.cognito.user-pool-id}") String userPoolId) {
        this.client = client;
        this.userPoolId = userPoolId;
    }

    @Override
    public Optional<UserEmail> resolve(String sub) {
        AdminGetUserResponse response;
        try {
            response = client.adminGetUser(AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(sub)
                    .build());
        } catch (UserNotFoundException e) {
            return Optional.empty();
        }

        String email = null;
        boolean verified = false;
        for (AttributeType attribute : response.userAttributes()) {
            if (EMAIL.equals(attribute.name())) {
                email = attribute.value();
            } else if (EMAIL_VERIFIED.equals(attribute.name())) {
                verified = "true".equalsIgnoreCase(attribute.value());
            }
        }
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new UserEmail(sub, email, verified));
    }
}
