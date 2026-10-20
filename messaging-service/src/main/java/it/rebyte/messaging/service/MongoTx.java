package it.rebyte.messaging.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mongodb.MongoException;

/**
 * Runs work in a Mongo transaction and retries it when the server labels the
 * failure transient (for example a write conflict with a concurrent
 * transaction). Any other failure, including a duplicate key, propagates at
 * once so the caller can decide what it means.
 */
@Component
public class MongoTx {

    private static final int MAX_ATTEMPTS = 10;
    private static final String TRANSIENT_LABEL = "TransientTransactionError";

    private final TransactionTemplate template;

    public MongoTx(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    public <T> T execute(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (RuntimeException failure) {
                if (attempt >= MAX_ATTEMPTS || !isTransient(failure)) {
                    throw failure;
                }
                pause();
            }
        }
    }

    public void run(Runnable work) {
        execute(() -> {
            work.run();
            return null;
        });
    }

    private static void pause() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1, 51));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a transaction", interrupted);
        }
    }

    private static boolean isTransient(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MongoException mongo && mongo.hasErrorLabel(TRANSIENT_LABEL)) {
                return true;
            }
        }
        return false;
    }
}
