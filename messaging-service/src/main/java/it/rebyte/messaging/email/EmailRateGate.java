package it.rebyte.messaging.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EmailRateGate {
    private final long intervalMillis;

    public EmailRateGate(@Value("${rebyte.ses.min-send-interval-ms}") long intervalMillis) {
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("Email send interval must be nonnegative");
        }
        this.intervalMillis = intervalMillis;
    }

    public synchronized void acquire() {
        try {
            // Waiting on every acquisition also covers a standby instance taking over the lane.
            Thread.sleep(intervalMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted before email send");
        }
    }
}
