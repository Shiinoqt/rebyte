package it.rebyte.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import it.rebyte.order.domain.License;
import it.rebyte.order.domain.LicenseStatus;
import it.rebyte.order.dto.LicensePage;
import it.rebyte.order.dto.LicenseView;
import it.rebyte.order.dto.LicenceCheckResponse;
import it.rebyte.order.dto.LicenceHolderPage;
import it.rebyte.order.error.InvalidLicenceCursorException;
import it.rebyte.order.repository.LicenseRepository;

@Service
public class LicenseQueryService {

    private static final int DEFAULT_MINE_SIZE = 20;
    private static final int MAX_MINE_SIZE = 50;
    private static final int DEFAULT_HOLDER_SIZE = 100;
    private static final int MAX_HOLDER_SIZE = 100;

    private final LicenseRepository licenses;

    public LicenseQueryService(LicenseRepository licenses) {
        this.licenses = licenses;
    }

    public LicensePage mine(String buyerSub, String cursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_MINE_SIZE : requestedSize;
        if (size < 1 || size > MAX_MINE_SIZE) {
            throw new InvalidLicenceCursorException();
        }
        MineCursor before = decodeMine(cursor);
        List<License> rows = licenses.mine(buyerSub, before.issuedAt(), before.orderId(), PageRequest.of(0, size + 1));
        int limit = Math.min(size, rows.size());
        List<LicenseView> items = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            items.add(view(rows.get(index)));
        }
        String next = rows.size() > limit ? encode(rows.get(limit - 1).getIssuedAt() + "|"
                + rows.get(limit - 1).getOrderId()) : null;
        return new LicensePage(List.copyOf(items), next);
    }

    public LicenceCheckResponse check(String buyerSub, String listingId) {
        return new LicenceCheckResponse(licenses.existsByBuyerSubAndListingIdAndStatus(
                buyerSub, listingId, LicenseStatus.ACTIVE));
    }

    public LicenceHolderPage holders(String listingId, String cursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_HOLDER_SIZE : requestedSize;
        if (size < 1 || size > MAX_HOLDER_SIZE) {
            throw new InvalidLicenceCursorException();
        }
        String after = decodeHolder(cursor);
        List<String> rows = licenses.holders(listingId, after, PageRequest.of(0, size + 1));
        int limit = Math.min(size, rows.size());
        List<String> items = List.copyOf(rows.subList(0, limit));
        String next = rows.size() > limit ? encode(rows.get(limit - 1)) : null;
        return new LicenceHolderPage(items, next);
    }

    private MineCursor decodeMine(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new MineCursor(null, null);
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidLicenceCursorException();
            }
            return new MineCursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidLicenceCursorException();
        }
    }

    private String decodeHolder(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (value.isBlank() || value.length() > 64 || value.indexOf('|') >= 0) {
                throw new InvalidLicenceCursorException();
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidLicenceCursorException();
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private LicenseView view(License license) {
        return new LicenseView(license.getKey(), license.getOrderId(), license.getListingId(),
                license.getListingTitle(), license.getStatus(), license.getIssuedAt(), license.getRevokedAt());
    }

    private record MineCursor(Instant issuedAt, String orderId) {
    }
}
