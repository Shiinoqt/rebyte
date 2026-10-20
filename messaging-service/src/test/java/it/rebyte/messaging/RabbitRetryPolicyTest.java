package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.boot.retry.RetryPolicySettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import it.rebyte.messaging.config.RabbitConfig;

class RabbitRetryPolicyTest {
    @Test
    void deterministicFailuresBypassBackoffWhileTransientFailuresRetry() {
        new ApplicationContextRunner().withUserConfiguration(RabbitConfig.class).run(context -> {
            assertThat(context).hasSingleBean(RabbitListenerRetrySettingsCustomizer.class);
            RetryPolicySettings settings = new RetryPolicySettings();
            context.getBean(RabbitListenerRetrySettingsCustomizer.class).customize(settings);
            var policy = settings.createRetryPolicy();
            for (Exception failure : List.of(new AmqpRejectAndDontRequeueException("reject"),
                    new IllegalArgumentException("invalid"), new JsonParseException(null, "invalid"),
                    new org.springframework.amqp.support.converter.MessageConversionException("invalid"),
                    new org.springframework.messaging.converter.MessageConversionException("invalid"))) {
                assertThat(policy.shouldRetry(failure)).isFalse();
                assertThat(policy.shouldRetry(new ListenerExecutionFailedException("listener", failure))).isFalse();
            }
            assertThat(policy.shouldRetry(new ListenerExecutionFailedException("listener",
                    new IllegalStateException("temporary failure")))).isTrue();
            assertThat(policy.shouldRetry(new ListenerExecutionFailedException("listener",
                    new it.rebyte.messaging.error.UserServiceUnavailableException()))).isTrue();
        });
    }
}
