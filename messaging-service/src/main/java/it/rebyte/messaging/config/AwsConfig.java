package it.rebyte.messaging.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import it.rebyte.commons.media.S3PostPolicySigner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Every AWS client of this module is bound to one dedicated IAM identity.
 * A leaked messaging key can send email and touch {@code chat/*} only, so it
 * must never be able to reach listing photos or paid software artifacts.
 * That is why startup fails on a missing key instead of resolving
 * credentials from the environment or the host.
 *
 * <p>The SES events queue lives in the SES region, not the media region.
 */
@Configuration
@Import(S3PostPolicySigner.class)
public class AwsConfig {

    @Bean
    AwsCredentialsProvider awsCredentialsProvider(
            @Value("${rebyte.messaging.aws.access-key-id:}") String accessKeyId,
            @Value("${rebyte.messaging.aws.secret-access-key:}") String secretAccessKey) {
        if (accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            throw new IllegalStateException("MESSAGING_AWS_ACCESS_KEY_ID and MESSAGING_AWS_SECRET_ACCESS_KEY "
                    + "must both be set; messaging-service never uses another AWS identity");
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    }

    @Bean
    S3Client s3Client(AwsCredentialsProvider provider, @Value("${rebyte.media.region}") String region) {
        return S3Client.builder().region(Region.of(region)).credentialsProvider(provider).build();
    }

    @Bean
    S3Presigner s3Presigner(AwsCredentialsProvider provider, @Value("${rebyte.media.region}") String region) {
        return S3Presigner.builder().region(Region.of(region)).credentialsProvider(provider).build();
    }

    @Bean
    SesV2Client sesV2Client(AwsCredentialsProvider provider, @Value("${rebyte.ses.region}") String region) {
        // SES has no idempotency token; only the dispatch owner can safely classify and retry refusals.
        return SesV2Client.builder().region(Region.of(region)).credentialsProvider(provider)
                .overrideConfiguration(config -> config.retryStrategy(StandardRetryStrategy.builder()
                        .maxAttempts(1).build())).build();
    }

    @Bean
    SqsClient sqsClient(AwsCredentialsProvider provider, @Value("${rebyte.ses.region}") String region) {
        return SqsClient.builder().region(Region.of(region)).credentialsProvider(provider).build();
    }
}
