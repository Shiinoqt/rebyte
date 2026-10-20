package it.rebyte.messaging.email;

/**
 * The emails messaging-service sends. Only an order payment confirmation is
 * transactional and ignores the recipient's email preference.
 */
public enum EmailKind {
    ORDER_PAID,
    NEW_VERSION,
    NEW_MESSAGE;

    public boolean isTransactional() {
        return this == ORDER_PAID;
    }
}
