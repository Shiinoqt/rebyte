package it.rebyte.order.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import it.rebyte.commons.error.AbstractApiExceptionHandler;
import it.rebyte.commons.error.DomainException;
import it.rebyte.commons.error.ProblemTypes;
import it.rebyte.order.error.PriceMismatchException;

/**
 * order-service's error-handling advice, mirroring {@code
 * UserApiExceptionHandler}/{@code CatalogApiExceptionHandler}. The only
 * advice in this module.
 */
@RestControllerAdvice
public class OrderApiExceptionHandler extends AbstractApiExceptionHandler {

    /**
     * The one {@link DomainException} subtype needing a per-type override:
     * {@link PriceMismatchException} carries the current total (D-09), an
     * extension the generic {@link #handleDomainException} in the base
     * class has no way to surface.
     */
    @ExceptionHandler(PriceMismatchException.class)
    public ResponseEntity<Object> handlePriceMismatch(PriceMismatchException ex, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setType(ProblemTypes.forCode(ex.getCode()));
        problemDetail.setTitle(ex.getCode());
        problemDetail.setProperty("currentTotal", ex.getCurrentTotal());
        return handleExceptionInternal(ex, problemDetail, new HttpHeaders(), ex.getStatus(), request);
    }
}
