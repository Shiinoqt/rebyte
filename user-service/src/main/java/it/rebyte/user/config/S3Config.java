package it.rebyte.user.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import it.rebyte.commons.media.S3PostPolicySigner;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 client wiring for the avatar upload flow (USER-03, D-34 AMENDED).
 *
 * <p>Credentials are resolved through the SDK's own standard {@link
 * DefaultCredentialsProvider} chain — environment variables, a credentials
 * file, or an instance/container role, unchanged between a developer
 * machine and a deployed instance. {@link S3PostPolicySigner} borrows this
 * SAME provider so the policy signature and the {@link S3Client} calls in
 * {@code AvatarService} (HeadObject on confirm, DeleteObject on
 * replacement) are always authenticated as the same principal.
 */
@Configuration
@Import(S3PostPolicySigner.class)
public class S3Config {

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return DefaultCredentialsProvider.create();
    }

    @Bean
    public S3Client s3Client(AwsCredentialsProvider credentialsProvider, @Value("${rebyte.media.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider)
                .build();
    }
}
