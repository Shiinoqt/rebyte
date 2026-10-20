package it.rebyte.messaging.service;

import it.rebyte.messaging.error.InvalidMessageTextException;

public final class MessageTextRules {

    private MessageTextRules() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw new InvalidMessageTextException();
        }
        String text = raw.strip();
        int length = text.codePointCount(0, text.length());
        if (length < 1 || length > 2000 || text.codePoints().anyMatch(MessageTextRules::forbidden)) {
            throw new InvalidMessageTextException();
        }
        return text;
    }

    public static String preview(String text) {
        if (text == null) {
            return null;
        }
        int length = text.codePointCount(0, text.length());
        return length > 140 ? text.substring(0, text.offsetByCodePoints(0, 140)) : text;
    }

    private static boolean forbidden(int codePoint) {
        return (codePoint < 0x20 && codePoint != '\t' && codePoint != '\n' && codePoint != '\r') || codePoint == 0x7f;
    }
}
