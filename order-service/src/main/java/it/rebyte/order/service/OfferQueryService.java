package it.rebyte.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.dto.OfferPage;
import it.rebyte.order.dto.OfferView;
import it.rebyte.order.error.InvalidOfferCursorException;
import it.rebyte.order.error.OfferNotFoundException;
import it.rebyte.order.repository.OfferRepository;

@Service
public class OfferQueryService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private final OfferRepository repository;

    public OfferQueryService(OfferRepository repository) {
        this.repository = repository;
    }

    public OfferView detail(String offerId, String subject, boolean admin) {
        Offer offer = repository.findById(offerId).orElseThrow(() -> new OfferNotFoundException(offerId));
        if (!admin && !subject.equals(offer.getBuyerSub()) && !subject.equals(offer.getSellerSub())) {
            throw new OfferNotFoundException(offerId);
        }
        return OfferView.from(offer);
    }

    public OfferPage sent(String subject, String listingId, String cursor, Integer size) {
        PageWindow window = window(cursor, size);
        return page(repository.sent(subject, listingId, window.beforeCreatedAt(), window.beforeId(),
                PageRequest.of(0, window.size() + 1)), window.size());
    }

    public OfferPage received(String subject, String listingId, String cursor, Integer size) {
        PageWindow window = window(cursor, size);
        return page(repository.received(subject, listingId, window.beforeCreatedAt(), window.beforeId(),
                PageRequest.of(0, window.size() + 1)), window.size());
    }

    private OfferPage page(List<Offer> rows, int requestedSize) {
        int limit = Math.min(rows.size(), requestedSize);
        List<OfferView> items = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            items.add(OfferView.from(rows.get(index)));
        }
        String nextCursor = rows.size() > limit ? encode(rows.get(limit - 1)) : null;
        return new OfferPage(List.copyOf(items), nextCursor);
    }

    private PageWindow window(String cursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidOfferCursorException();
        }
        if (cursor == null || cursor.isBlank()) {
            return new PageWindow(null, null, size);
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidOfferCursorException();
            }
            return new PageWindow(Instant.parse(parts[0]), parts[1], size);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidOfferCursorException();
        }
    }

    private String encode(Offer offer) {
        String value = offer.getCreatedAt() + "|" + offer.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record PageWindow(Instant beforeCreatedAt, String beforeId, int size) {
    }
}
