package it.rebyte.messaging.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * Enables {@code @Scheduled} and gives the periodic jobs their own pool: the
 * outbox relay, the outbox reaper, the outbox purge, the chat image sweep,
 * the WebSocket session expiry sweep and the presence refresh. The default
 * single-threaded scheduler would let a long purge or sweep delay the
 * relay's one-second tick, so a pool reduces delays between jobs.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService schedulingExecutor() {
        return Executors.newScheduledThreadPool(5);
    }

    @Bean
    public TaskScheduler taskScheduler(ScheduledExecutorService schedulingExecutor) {
        return new ConcurrentTaskScheduler(schedulingExecutor);
    }
}
