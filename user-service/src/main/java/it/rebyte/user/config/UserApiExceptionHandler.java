package it.rebyte.user.config;

import org.springframework.web.bind.annotation.RestControllerAdvice;

import it.rebyte.commons.error.AbstractApiExceptionHandler;
import it.rebyte.commons.error.DomainException;

/**
 * user-service's error-handling advice (D-13, D-30). The <b>only</b> advice
 * in this module — extends the commons base directly, with no
 * service-specific {@code @ExceptionHandler} methods of its own, because
 * every {@link DomainException} subtype this service throws (see
 * {@code it.rebyte.user.error}) already carries its own HTTP status and
 * code, so {@link AbstractApiExceptionHandler}'s generic handler covers all
 * of them.
 */
@RestControllerAdvice
public class UserApiExceptionHandler extends AbstractApiExceptionHandler {
}
