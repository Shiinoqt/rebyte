package it.rebyte.payment.domain;

/**
 * docs/rebyte_progettazione.md §5.2: {@code PaymentMethod: {bankTransfer,
 * creditCard, payPal}}. payment-service's own copy (never shared through
 * {@code it.rebyte.commons}), constant names kept byte-for-byte identical to
 * order-service's {@code PaymentMethod} so Feign can deserialize {@code
 * POST /internal/payments}'s {@code method} field directly into this type.
 *
 * <p>{@code BANK_TRANSFER} is refused at checkout by order-service
 * (03-CONTEXT.md D-20) and never sent here in practice; {@link
 * it.rebyte.payment.service.PaymentService} still rejects it defensively
 * rather than trusting the caller.
 */
public enum PaymentMethod {
    BANK_TRANSFER,
    CREDIT_CARD,
    PAYPAL
}
