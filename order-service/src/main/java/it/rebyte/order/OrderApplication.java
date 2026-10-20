package it.rebyte.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bootstrap for order-service: checkout, the single-item MySQL race guard
 * and order state (03-CONTEXT.md D-01, D-16, D-17).
 *
 * <p>{@code @EnableFeignClients} is required from Task 1 itself, unlike
 * user-service/catalog-service, which added it lazily on their first Feign
 * client plan — order-service's first task already calls catalog and
 * payment over {@code /internal/**}.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
