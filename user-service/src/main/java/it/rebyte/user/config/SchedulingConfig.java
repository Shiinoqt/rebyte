package it.rebyte.user.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * Enables {@code @Scheduled} and gives {@link it.rebyte.user.outbox.OutboxRelay}
 * and {@link it.rebyte.user.outbox.OutboxPurgeJob} their own small pool
 * (D-20). {@code @EnableScheduling}'s default is a single-threaded
 * scheduler shared by every {@code @Scheduled} method in the context; on
 * that default, the daily purge job running long could delay the relay's
 * one-second tick. A dedicated two-thread pool keeps the two jobs
 * independent.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        return new ConcurrentTaskScheduler(Executors.newScheduledThreadPool(2));
    }
}
