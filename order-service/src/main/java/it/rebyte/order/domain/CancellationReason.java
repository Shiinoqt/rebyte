package it.rebyte.order.domain;

public enum CancellationReason {
    PAYMENT_DECLINED,
    TIMEOUT,
    BUYER_ABORTED,
    PAYMENT_UNAVAILABLE,
    LISTING_UNAVAILABLE
}
