package it.rebyte.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /internal/orders/{orderId}/capture-authorizations} request,
 * consumed by payment-service (a later plan). {@code operationId} is
 * payment-service's own durable claim id: repeating the same id against an
 * already-{@code CAPTURE_AUTHORIZED} order is granted again (idempotent
 * retry); a different id is denied.
 */
public record CaptureAuthorizationRequest(@NotBlank String operationId) {
}
