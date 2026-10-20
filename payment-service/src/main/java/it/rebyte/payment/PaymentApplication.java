package it.rebyte.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Bootstrap for payment-service: PayPal order create/capture behind an
 * operation gate, isolated from order-service's own database (03-CONTEXT.md
 * D-01, D-02, D-21, D-23).
 *
 * <p>{@code @EnableFeignClients} is required from Task 1 itself, mirroring
 * order-service: this module's capture task (Task 2) calls order-service's
 * {@code /internal/orders/{id}/capture-authorizations} synchronously.
 *
 * <p>{@code @EnableScheduling} moved to {@link
 * it.rebyte.payment.config.PaymentSchedulingConfig} (03-10-PLAN.md Task 1),
 * which now also sizes a dedicated scheduler pool for the outbox relay and
 * the new reconciliation sweep — same pattern as user-service/catalog-service.
 */
@SpringBootApplication
@EnableFeignClients
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
