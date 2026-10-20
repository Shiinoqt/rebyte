package it.rebyte.commons.error;

import java.net.URI;

/**
 * The {@code type} URI convention shared by every service's problem
 * responses (D-31): a stable base under the project's own namespace, plus a
 * helper that builds a per-code URI from it, so five services emit the same
 * shape instead of accreting five separate conventions.
 *
 * <p>{@link #BASE_URI} is an <b>identifier, not a live URL</b> — RFC 9457
 * only requires that {@code type} identify the problem type, never that it
 * resolve to a document. Do not expect a client to successfully {@code GET}
 * it, and do not build tooling that assumes it will.
 */
public final class ProblemTypes {

    /**
     * Non-dereferenceable identifier namespace for every problem {@code
     * type} this project emits. A code is appended to form the full URI —
     * see {@link #forCode(String)}.
     */
    public static final String BASE_URI = "urn:rebyte:problem:";

    private ProblemTypes() {
    }

    /**
     * Builds the {@code type} URI for a given stable, machine-readable
     * problem code (see {@link DomainException#getCode()}).
     */
    public static URI forCode(String code) {
        return URI.create(BASE_URI + code);
    }
}
