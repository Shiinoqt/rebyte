package it.rebyte.catalog.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * Enables {@code @Scheduled} and gives {@link it.rebyte.catalog.outbox.OutboxRelay},
 * {@link it.rebyte.catalog.outbox.OutboxReaper} and
 * {@link it.rebyte.catalog.outbox.OutboxPurgeJob} their own small pool,
 * mirroring user-service's {@code SchedulingConfig}. The default single
 * threaded scheduler would let a long purge or reaper sweep delay the
 * relay's one-second tick; a dedicated pool keeps the jobs independent.
 * Three jobs share this pool now (one more than user-service's two), so the
 * pool is sized to match.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        return new ConcurrentTaskScheduler(Executors.newScheduledThreadPool(3));
    }
}
