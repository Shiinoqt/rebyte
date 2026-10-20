package it.rebyte.order.domain;

/**
 * docs/rebyte_progettazione.md §5.2: {@code PaymentMethod: {bankTransfer,
 * creditCard, payPal}}. order-service's own copy (never shared through
 * {@code it.rebyte.commons}), constant names kept byte-for-byte identical to
 * catalog-service's {@code PaymentMethod} so Feign can deserialize
 * {@code listing.paymentMethods} directly into this type.
 *
 * <p>{@code BANK_TRANSFER} is accepted by catalog listings but always
 * refused at checkout (03-CONTEXT.md D-20): a seller-confirmed path to
 * {@code PAID} contradicts the webhook-only-PAID guarantee (criterion 2).
 */
public enum PaymentMethod {
    BANK_TRANSFER,
    CREDIT_CARD,
    PAYPAL
}
