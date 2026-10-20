package it.rebyte.order.client;

import feign.FeignException;

/**
 * Feign clients here run inside the Resilience4j circuit breaker, which rethrows
 * a remote 404 as NoFallbackAvailableException with the FeignException as cause.
 */
public final class FeignErrors {

    private FeignErrors() {
    }

    public static boolean isNotFound(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FeignException.NotFound) {
                return true;
            }
        }
        return false;
    }
    public static boolean isConflict(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FeignException.Conflict) return true;
        }
        return false;
    }
}
