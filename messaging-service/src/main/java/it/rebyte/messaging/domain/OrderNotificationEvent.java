package it.rebyte.messaging.domain;

public enum OrderNotificationEvent {
    PAID,
    SHIPPED,
    DELIVERED,
    REFUNDED,
    PROBLEM_OPENED,
    PROBLEM_RESOLVED
}
