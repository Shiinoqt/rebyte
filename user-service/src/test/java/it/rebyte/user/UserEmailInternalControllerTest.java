package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.rebyte.user.controller.internal.UserEmailInternalController;
import it.rebyte.user.dto.UserEmail;
import it.rebyte.user.error.EmailDirectoryUnavailableException;
import it.rebyte.user.error.UserEmailNotFoundException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;

class UserEmailInternalControllerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(UserEmailInternalController.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureWarnings() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingWarnings() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void directoryFailureLogsOnlyClassAndKeepsThe503Generic() {
        RuntimeException failure = new IllegalStateException("Access denied",
                new RuntimeException("sensitive nested cause"));
        UserEmailInternalController controller = new UserEmailInternalController(sub -> {
            throw failure;
        });

        assertThatThrownBy(() -> controller.email("recipient@example.test"))
                .isInstanceOfSatisfying(EmailDirectoryUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getCode()).isEqualTo("email-directory-unavailable");
                    assertThat(exception.getMessage()).isEqualTo("email directory is unavailable");
                    assertThat(exception.getCause()).isSameAs(failure);
                });
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("IllegalStateException")
                    .doesNotContain("Access denied", "recipient@example.test", "sensitive nested cause");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lookup failed for recipient@example.test", "Lookup failed for phone_number=5550100"})
    void directoryMessageIsNeverLogged(String message) {
        UserEmailInternalController controller = new UserEmailInternalController(sub -> {
            throw new IllegalArgumentException(message);
        });

        assertThatThrownBy(() -> controller.email("recipient@example.test"))
                .isInstanceOf(EmailDirectoryUnavailableException.class);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("IllegalArgumentException")
                    .doesNotContain(message, "recipient@example.test", "5550100");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Lookup failed for recipient@example.test", "Lookup failed for phone_number=5550100"})
    void awsDirectoryFailureLogsOnlyClassCodeAndStatus(String message) {
        AwsServiceException failure = CognitoIdentityProviderException.builder()
                .message(message)
                .requestId("sensitive-request-id")
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("AccessDeniedException")
                        .errorMessage("sensitive provider attributes")
                        .build())
                .build();
        UserEmailInternalController controller = new UserEmailInternalController(sub -> {
            throw failure;
        });

        assertThatThrownBy(() -> controller.email("recipient@example.test"))
                .isInstanceOf(EmailDirectoryUnavailableException.class)
                .hasCause(failure);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("CognitoIdentityProviderException", "AccessDeniedException", "403")
                    .doesNotContain(message, "recipient@example.test", "5550100",
                            "sensitive provider attributes", "sensitive-request-id");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void awsDirectoryFailureWithoutErrorDetailsStillLogsClassAndStatus() {
        AwsServiceException failure = AwsServiceException.builder()
                .message("sensitive provider attributes")
                .statusCode(503)
                .build();
        UserEmailInternalController controller = new UserEmailInternalController(sub -> {
            throw failure;
        });

        assertThatThrownBy(() -> controller.email("sub-1"))
                .isInstanceOf(EmailDirectoryUnavailableException.class)
                .hasCause(failure);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("AwsServiceException", "503")
                    .doesNotContain("sensitive provider attributes");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void directoryFailureWithoutAMessageStillLogsItsClass() {
        UserEmailInternalController controller = new UserEmailInternalController(sub -> {
            throw new IllegalStateException();
        });

        assertThatThrownBy(() -> controller.email("sub-1"))
                .isInstanceOf(EmailDirectoryUnavailableException.class);
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("IllegalStateException"));
    }

    @Test
    void successfulAndMissingLookupsDoNotLogFailures() {
        UserEmail email = new UserEmail("sub-1", "recipient@example.test", true);
        UserEmailInternalController successful = new UserEmailInternalController(sub -> Optional.of(email));
        UserEmailInternalController missing = new UserEmailInternalController(sub -> Optional.empty());

        assertThat(successful.email("sub-1")).isEqualTo(email);
        assertThatThrownBy(() -> missing.email("sub-1")).isInstanceOf(UserEmailNotFoundException.class);
        assertThat(appender.list).isEmpty();
    }
}
