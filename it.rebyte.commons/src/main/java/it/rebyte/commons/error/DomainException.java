package it.rebyte.commons.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for every domain exception across services (D-13, D-31).
 *
 * <p>Carries the HTTP status the exception should produce and a stable,
 * machine-readable {@code code} used both as the {@code title} of the
 * resulting {@link org.springframework.http.ProblemDetail} and as the last
 * segment of its {@code type} URI (see {@link ProblemTypes}). Concrete
 * domain exception <em>subtypes</em> stay local to the service that owns
 * them — only this base type lives in {@code it.rebyte.commons} (D-31).
 */
public abstract class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected DomainException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
