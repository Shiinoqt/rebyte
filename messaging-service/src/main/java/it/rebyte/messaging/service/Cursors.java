package it.rebyte.messaging.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;

import it.rebyte.messaging.error.InvalidCursorException;

/**
 * Page size and cursor rules shared by every paged endpoint. Cursors are
 * opaque Base64url text without padding; every decode failure is reported as
 * {@link InvalidCursorException} so a tampered cursor is a 400 and never a
 * server error.
 */
public final class Cursors {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    public record InstantIdCursor(Instant at, String id) {
    }

    private Cursors() {
    }

    public static int pageSize(Integer requested) {
        int size = requested == null ? DEFAULT_PAGE_SIZE : requested;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidCursorException();
        }
        return size;
    }

    public static String encodeInstantId(Instant at, String id) {
        return encode(at + "|" + id);
    }

    /** Returns null when there is no cursor, which means the first page. */
    public static InstantIdCursor decodeInstantId(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = decode(cursor).split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidCursorException();
            }
            return new InstantIdCursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidCursorException();
        }
    }

    public static String encodeSeq(long seq) {
        if (seq < 1) {
            throw new IllegalArgumentException("seq must be positive");
        }
        return encode(Long.toString(seq));
    }

    /** Returns null when there is no cursor, which means the first page. */
    public static Long decodeSeq(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long seq = Long.parseLong(decode(cursor));
            if (seq < 1) {
                throw new InvalidCursorException();
            }
            return seq;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidCursorException();
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }
}
