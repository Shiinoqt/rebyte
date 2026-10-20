package it.rebyte.payment.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * Enables {@code @Scheduled} and gives {@link
 * it.rebyte.payment.outbox.OutboxRelay} and {@link
 * it.rebyte.payment.service.PaymentReconciliationService}'s sweep their own
 * small pool (03-10-PLAN.md Task 1), mirroring user-service's and
 * catalog-service's {@code SchedulingConfig}. The default single-threaded
 * scheduler would let a long reconciliation sweep (one PayPal GET per due
 * payment) delay the outbox relay's one-second tick; a dedicated pool keeps
 * the two jobs independent. Replaces the {@code @EnableScheduling} that
 * previously sat directly on {@code PaymentApplication} — same effect, now
 * paired with the pool.
 */
@Configuration
@EnableScheduling
public class PaymentSchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        return new ConcurrentTaskScheduler(Executors.newScheduledThreadPool(2));
    }
}
