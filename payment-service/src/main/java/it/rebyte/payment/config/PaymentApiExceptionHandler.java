package it.rebyte.payment.config;

import org.springframework.web.bind.annotation.RestControllerAdvice;

import it.rebyte.commons.error.AbstractApiExceptionHandler;

/**
 * payment-service's error-handling advice, mirroring {@code
 * OrderApiExceptionHandler}/{@code UserApiExceptionHandler}. Every domain
 * exception in this module carries its own status/code/message via {@link
 * it.rebyte.commons.error.DomainException}, so the generic {@code
 * handleDomainException} in the base class needs no per-subtype override
 * here (unlike order-service's {@code PriceMismatchException}, which
 * carries an extra structured field).
 */
@RestControllerAdvice
public class PaymentApiExceptionHandler extends AbstractApiExceptionHandler {
}
