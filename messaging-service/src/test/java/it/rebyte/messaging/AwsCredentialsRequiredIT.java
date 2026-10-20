package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import it.rebyte.commons.media.S3PostPolicySigner;
import it.rebyte.messaging.config.AwsConfig;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Starts only {@link AwsConfig}: the startup contract does not need Mongo,
 * Redis or any container.
 */
class AwsCredentialsRequiredIT {

    private static final String KEY = "AKIAMESSAGINGKEYONLY00";
    private static final String SECRET = "messaging-secret-only-not-real";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(AwsConfig.class))
            .withPropertyValues("rebyte.media.region=eu-south-1", "rebyte.ses.region=eu-south-1");

    @Test
    void bothKeysBlankFailsNamingTheVariables() {
        runner.withPropertyValues(
                        "rebyte.messaging.aws.access-key-id=",
                        "rebyte.messaging.aws.secret-access-key=")
                .run(context -> assertRefusesToStart(context, List.of()));
    }

    @Test
    void bothKeysAbsentFails() {
        runner.run(context -> assertRefusesToStart(context, List.of()));
    }

    @Test
    void onlyAccessKeyFailsWithoutDisclosingIt() {
        runner.withPropertyValues(
                        "rebyte.messaging.aws.access-key-id=" + KEY,
                        "rebyte.messaging.aws.secret-access-key=")
                .run(context -> assertRefusesToStart(context, List.of(KEY)));
    }

    @Test
    void onlySecretFailsWithoutDisclosingIt() {
        runner.withPropertyValues(
                        "rebyte.messaging.aws.access-key-id=",
                        "rebyte.messaging.aws.secret-access-key=" + SECRET)
                .run(context -> assertRefusesToStart(context, List.of(SECRET)));
    }

    @Test
    void sharedAwsKeysDoNotSubstituteTheMessagingKeys() {
        runner.withPropertyValues(
                        "AWS_ACCESS_KEY_ID=AKIASHAREDKEYNOTUSED0",
                        "AWS_SECRET_ACCESS_KEY=shared-secret-must-not-be-used",
                        "rebyte.messaging.aws.access-key-id=",
                        "rebyte.messaging.aws.secret-access-key=")
                .run(context -> assertRefusesToStart(
                        context, List.of("AKIASHAREDKEYNOTUSED0", "shared-secret-must-not-be-used")));
    }

    @Test
    void bothKeysSetStartsAndExposesTheClients() {
        runner.withPropertyValues(
                        "rebyte.messaging.aws.access-key-id=" + KEY,
                        "rebyte.messaging.aws.secret-access-key=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(S3Client.class);
                    assertThat(context).hasSingleBean(S3Presigner.class);
                    assertThat(context).hasSingleBean(SesV2Client.class);
                    assertThat(context).hasSingleBean(SqsClient.class);
                    assertThat(context).hasSingleBean(S3PostPolicySigner.class);
                });
    }

    private static void assertRefusesToStart(AssertableApplicationContext context, List<String> mustNotAppear) {
        assertThat(context).hasFailed();
        List<String> messages = new ArrayList<>();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            messages.add(String.valueOf(t.getMessage()));
        }
        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains("MESSAGING_AWS_ACCESS_KEY_ID")
                .contains("MESSAGING_AWS_SECRET_ACCESS_KEY"));
        for (String secret : mustNotAppear) {
            assertThat(messages).noneSatisfy(message -> assertThat(message).contains(secret));
        }
    }
}
