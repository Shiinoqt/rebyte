package it.rebyte.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import it.rebyte.order.dto.PaymentCreateRequest;
import it.rebyte.order.dto.PaymentCreateResponse;
import it.rebyte.order.dto.PaymentStatusResponse;
import it.rebyte.order.dto.RefundRequest;
import it.rebyte.order.dto.RefundResponse;

/**
 * payment-service's {@code /internal/payments} contract (D-01). payment-
 * service is built in a later plan (03-05); this is order-service's half
 * of the contract, exercised against a stub HTTP server in this plan's
 * *IT classes. Short connect/read timeouts and the circuit breaker are
 * configured per-client in {@code application.yml} (D-37).
 */
@FeignClient(name = "payment-service")
public interface PaymentInternalClient {

    @PostMapping("/internal/payments")
    PaymentCreateResponse create(@RequestBody PaymentCreateRequest request);

    @PostMapping("/internal/payments/{orderId}/refund")
    RefundResponse refund(@PathVariable String orderId, @RequestBody RefundRequest request);

    @GetMapping("/internal/payments/{orderId}/status")
    PaymentStatusResponse status(@PathVariable String orderId);
}
