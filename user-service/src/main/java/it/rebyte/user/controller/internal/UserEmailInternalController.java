package it.rebyte.user.controller.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.commons.error.DomainException;
import it.rebyte.user.dto.UserEmail;
import it.rebyte.user.error.EmailDirectoryUnavailableException;
import it.rebyte.user.error.UserEmailNotFoundException;
import it.rebyte.user.service.UserEmailDirectory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

/**
 * {@code GET /internal/users/{sub}/email} (D-04), the recipient lookup for
 * messaging-service. Lives in {@code it.rebyte.user.controller.internal} for
 * the same reason as {@link UserInternalController}: the gateway denies the
 * {@code /internal/**} prefix and springdoc excludes this package, so a
 * mapping placed elsewhere would be silently routable and published.
 *
 * <p>No authorization check by design; the internal surface trusts the
 * internal network. The address is never logged or stored here.
 */
@RestController
@RequestMapping("/internal/users")
public class UserEmailInternalController {

    private static final Logger log = LoggerFactory.getLogger(UserEmailInternalController.class);

    private final UserEmailDirectory directory;

    public UserEmailInternalController(UserEmailDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/{sub}/email")
    public UserEmail email(@PathVariable String sub) {
        try {
            return directory.resolve(sub).orElseThrow(() -> new UserEmailNotFoundException(sub));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof AwsServiceException awsFailure) {
                String errorCode = awsFailure.awsErrorDetails() == null
                        ? null : awsFailure.awsErrorDetails().errorCode();
                log.warn("Email directory lookup failed: {}, AWS error code: {}, HTTP status: {}",
                        e.getClass().getSimpleName(), errorCode, awsFailure.statusCode());
            } else {
                log.warn("Email directory lookup failed: {}", e.getClass().getSimpleName());
            }
            throw new EmailDirectoryUnavailableException(e);
        }
    }
}
