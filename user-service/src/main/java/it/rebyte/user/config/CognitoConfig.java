package it.rebyte.user.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Cognito client for the internal email lookup (D-04). Builds its own
 * credentials provider on the same default chain as {@link S3Config} instead
 * of declaring a second {@code AwsCredentialsProvider} bean, which would make
 * the single-provider injection in the S3 signer ambiguous.
 */
@Configuration
@Profile("cognito")
public class CognitoConfig {

    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient(
            @Value("${rebyte.cognito.region}") String region,
            @Value("${rebyte.cognito.user-pool-id}") String userPoolId) {
        if (userPoolId == null || userPoolId.isBlank()) {
            throw new IllegalStateException("rebyte.cognito.user-pool-id (COGNITO_USER_POOL_ID) must be set under the cognito profile");
        }
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
