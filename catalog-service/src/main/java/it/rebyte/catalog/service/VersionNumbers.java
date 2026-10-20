package it.rebyte.catalog.service;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import it.rebyte.catalog.error.InvalidVersionNumberException;

public final class VersionNumbers {
    private static final int MAX_COMPONENTS = 8;
    private static final int MAX_DIGITS = 9;
    private VersionNumbers() {}

    public static int compare(String left, String right) {
        BigInteger[] a = parse(left);
        BigInteger[] b = parse(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            BigInteger av = i < a.length ? a[i] : BigInteger.ZERO;
            BigInteger bv = i < b.length ? b[i] : BigInteger.ZERO;
            int compared = av.compareTo(bv);
            if (compared != 0) return compared;
        }
        return 0;
    }

    public static Comparator<String> comparator() { return VersionNumbers::compare; }

    private static BigInteger[] parse(String value) {
        if (value == null || value.isBlank()) throw new InvalidVersionNumberException(value);
        String[] parts = value.split("\\.", -1);
        if (parts.length > MAX_COMPONENTS) throw new InvalidVersionNumberException(value);
        if (Arrays.stream(parts).anyMatch(p -> !p.matches("\\d{1," + MAX_DIGITS + "}"))) {
            throw new InvalidVersionNumberException(value);
        }
        return Arrays.stream(parts).map(BigInteger::new).toArray(BigInteger[]::new);
    }
}
