package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import it.rebyte.messaging.email.SesEmailClient;

class SesEmailClientTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankSenderFailsStartup(String address) {
        new ApplicationContextRunner().withBean(SesEmailClient.class,
                () -> new SesEmailClient(mock(SesV2Client.class), address, "transactional"))
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("SES_FROM_ADDRESS must be set"));
    }

    @Test
    void configuredSenderStarts() {
        new ApplicationContextRunner().withBean(SesEmailClient.class,
                () -> new SesEmailClient(mock(SesV2Client.class), "no-reply@example.test", "transactional"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SesEmailClient.class));
    }
}
