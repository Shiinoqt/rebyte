package it.rebyte.user.controller.internal;

/**
 * The single source of truth for the {@code /internal/**} path prefix
 * (D-56, T-05-02) — referenced by {@link
 * it.rebyte.user.config.SecurityConfig} to permit the prefix without
 * authentication. Keeping the literal string itself confined to this
 * package (rather than repeated inline in {@code SecurityConfig}) is what
 * lets a grep for the literal prefix, scoped to this package, actually
 * assert "no internal path mapping lives outside {@code
 * controller.internal}" — a copy of the same literal string inlined in
 * {@code SecurityConfig} would be an unrelated security matcher, not a
 * route mapping, and would otherwise trip that same grep as a false
 * positive.
 */
public final class InternalApi {

    public static final String PATH_PREFIX = "/internal/**";

    private InternalApi() {
    }
}
