package it.rebyte.payment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.constraints.NotBlank;

/**
 * payment-service's one Feign gateway into order-service's {@code
 * /internal/**} surface: {@code POST
 * /internal/orders/{orderId}/capture-authorizations} (D-02 amendment;
 * payment-service claims the durable capture operation before ever calling
 * PayPal) and {@code POST /internal/offers/expire-due} (03-10-PLAN.md Task
 * 1, D-25 amendment; the reconciliation sweep triggers accepted-offer hold
 * expiry once per cycle without ever reading order-service's database).
 * Deliberately ONE {@code @FeignClient(name = "order-service")} interface —
 * a second one targeting the same name collides on Spring's {@code
 * FeignClientSpecification} bean registration, and a distinct {@code
 * contextId} would decouple this client from the {@code
 * spring.cloud.openfeign.client.config.order-service.*} timeout/URL
 * properties both this class and the tests rely on. Request/response shapes
 * for the grant are byte-for-byte order-service's own {@code
 * CaptureAuthorizationRequest}/{@code CaptureAuthorizationResponse}
 * (order-service/src/main/java/it/rebyte/order/dto/), kept as nested
 * records here rather than separate files, same as this plan's own {@code
 * ExpireDueResponse} mirrors order-service's {@code
 * OfferExpiryInternalController.ExpireDueResponse}.
 */
@FeignClient(name = "order-service")
public interface OrderCaptureAuthorizationClient {

    @PostMapping("/internal/orders/{orderId}/capture-authorizations")
    CaptureAuthorizationResponse grant(@PathVariable String orderId, @RequestBody CaptureAuthorizationRequest request);

    @PostMapping("/internal/offers/expire-due")
    ExpireDueResponse expireDueOffers();

    record CaptureAuthorizationRequest(@NotBlank String operationId) {
    }

    record CaptureAuthorizationResponse(Outcome outcome, String checkoutPhase) {
        public enum Outcome { GRANTED, ALREADY_GRANTED, DENIED }
    }

    record ExpireDueResponse(int expiredCount) {
    }
}
