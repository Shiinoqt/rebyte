package it.rebyte.catalog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import it.rebyte.commons.media.S3PostPolicySigner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Catalog-specific credentials locally; the SDK provider chain supports deployed roles. */
@Configuration
@Import(S3PostPolicySigner.class)
public class S3Config {
    @Bean
    AwsCredentialsProvider awsCredentialsProvider(
            @Value("${CATALOG_AWS_ACCESS_KEY_ID:}") String key,
            @Value("${CATALOG_AWS_SECRET_ACCESS_KEY:}") String secret) {
        if (key.isBlank() != secret.isBlank()) {
            throw new IllegalStateException("Both catalog AWS credential variables must be configured together");
        }
        return key.isBlank() ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret));
    }

    @Bean
    S3Client s3Client(AwsCredentialsProvider provider, @Value("${rebyte.media.region}") String region) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(provider).build();
    }

    @Bean
    S3Presigner s3Presigner(AwsCredentialsProvider provider, @Value("${rebyte.media.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).credentialsProvider(provider).build();
    }
}
