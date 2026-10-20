package it.rebyte.order.controller.internal;

/**
 * Single source of truth for the {@code /internal/**} path prefix (mirrors
 * user-service's/catalog-service's {@code InternalApi}) — referenced by
 * {@link it.rebyte.order.config.SecurityConfig} to permit the prefix
 * without authentication. The gateway denies this prefix outright and
 * springdoc excludes it from the public API spec.
 */
public final class InternalApi {

    public static final String PATH_PREFIX = "/internal/**";

    private InternalApi() {
    }
}
