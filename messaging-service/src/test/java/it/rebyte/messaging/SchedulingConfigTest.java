package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import it.rebyte.messaging.config.SchedulingConfig;

class SchedulingConfigTest {

    @Test
    void closingTheContextShutsDownTheExecutorAndCancelsPeriodicJobs() throws Exception {
        var context = new AnnotationConfigApplicationContext(SchedulingConfig.class);
        var scheduler = (ConcurrentTaskScheduler) context.getBean(TaskScheduler.class);
        var executor = (ScheduledExecutorService) scheduler.getConcurrentExecutor();
        CountDownLatch ran = new CountDownLatch(1);
        ScheduledFuture<?> task;
        boolean shutDownByContext;
        try {
            task = scheduler.scheduleAtFixedRate(ran::countDown, Instant.now(), Duration.ofHours(1));
            assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            context.close();
            shutDownByContext = executor.isShutdown();
            if (!shutDownByContext) {
                executor.shutdownNow();
            }
        }
        assertThat(context.isActive()).isFalse();
        assertThat(task.isCancelled()).isTrue();
        assertThat(shutDownByContext).isTrue();
    }
}
