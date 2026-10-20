package it.rebyte.commons.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Abstract base for every service's error-handling advice (D-13, D-30,
 * D-31).
 *
 * <p><b>Must extend {@link ResponseEntityExceptionHandler} — not sit beside
 * it.</b> Verified against Spring Boot 4.1.0: registering a custom advice
 * that extends this class makes the auto-configured
 * {@code ProblemDetailsExceptionHandler} back off entirely. A sibling
 * {@code @ControllerAdvice} (one that does not extend
 * {@code ResponseEntityExceptionHandler}) would silently lose framework
 * exception handling for built-in exceptions (unsupported media type,
 * malformed body, missing parameter, ...) with no error to notice — the
 * response would revert to a bare Servlet-container error page.
 *
 * <p>Each service extends this class with its own concrete {@code
 * @RestControllerAdvice} (e.g. {@code UserApiExceptionHandler}), adding
 * handlers for that service's own {@link DomainException} subtypes if
 * needed — though because {@link DomainException} itself already carries
 * the HTTP status and code, the generic {@link #handleDomainException}
 * below usually needs no per-subtype override. Body construction always
 * uses {@link ProblemDetail} — never a hand-rolled JSON error envelope
 * (D-30).
 */
@RestControllerAdvice
public abstract class AbstractApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String VALIDATION_FAILED_CODE = "validation-failed";

    /**
     * Translates any {@link DomainException} into a {@link ProblemDetail}
     * carrying the status and {@code type} URI the exception itself
     * declares. Jackson favors {@code application/problem+json} for
     * {@link ProblemDetail} bodies automatically during content
     * negotiation — no explicit header is set here.
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Object> handleDomainException(DomainException ex, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setType(ProblemTypes.forCode(ex.getCode()));
        problemDetail.setTitle(ex.getCode());
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), ex.getStatus(), request);
    }

    /**
     * A {@code @Valid} failure produces a 400 {@link ProblemDetail} carrying
     * an {@code errors} extension listing field and message per failure,
     * instead of the framework default's bare field-error array with no
     * stable member name.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, "Validation failed");
        problemDetail.setTitle(VALIDATION_FAILED_CODE);
        problemDetail.setType(ProblemTypes.forCode(VALIDATION_FAILED_CODE));

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problemDetail.setProperty("errors", errors);

        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }
}
