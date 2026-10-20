package it.rebyte.catalog.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import it.rebyte.commons.error.AbstractApiExceptionHandler;
import it.rebyte.commons.error.DomainException;
import it.rebyte.commons.error.ProblemTypes;

/**
 * catalog-service's error-handling advice, mirroring
 * {@code UserApiExceptionHandler}. The <b>only</b> advice in this module —
 * a second sibling {@code @RestControllerAdvice} would make Boot's
 * auto-configured problem handler back off entirely (see
 * {@link AbstractApiExceptionHandler}'s Javadoc). Every {@link DomainException}
 * subtype this service throws already carries its own HTTP status and code,
 * so the generic handler below covers all of them with no per-subtype
 * override.
 */
@RestControllerAdvice
public class CatalogApiExceptionHandler extends AbstractApiExceptionHandler {

    private static final String ACCESS_DENIED_CODE = "access-denied";

    /**
     * Not in the plan's Task 1 file list — added here (Rule 2: missing
     * critical functionality) because {@code CategoryController#create} is
     * this service's first {@code @PreAuthorize}-gated endpoint (ADMIN-01).
     * Spring Security throws {@link AccessDeniedException} (or its {@code
     * AuthorizationDeniedException} subtype) from WITHIN the controller
     * method's AOP-proxied call, so it reaches this advice through the
     * ordinary {@code @ExceptionHandler} path, NOT Spring Security's
     * {@code AccessDeniedHandler} (that only fires for denials at the
     * servlet-filter level, e.g. URL-pattern authorization). {@code
     * commons}'s {@link AbstractApiExceptionHandler} has no handler for it —
     * it is not a {@link DomainException} — so without this override the
     * response would fall back to a bare servlet error page instead of the
     * RFC 9457 body the "403 with an RFC 9457 body" acceptance criterion
     * requires.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access is denied");
        problemDetail.setType(ProblemTypes.forCode(ACCESS_DENIED_CODE));
        problemDetail.setTitle(ACCESS_DENIED_CODE);
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }
}
