package it.rebyte.order.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import it.rebyte.order.client.UserInternalClient;
import it.rebyte.order.domain.Review;
import it.rebyte.order.dto.ReviewPage;
import it.rebyte.order.dto.ReviewView;
import it.rebyte.order.dto.UserSummaryResponse;
import it.rebyte.order.error.InvalidReviewCursorException;
import it.rebyte.order.error.ReviewNotFoundException;
import it.rebyte.order.repository.ReviewRepository;

@Service
public class ReviewQueryService {

    private static final Logger log = LoggerFactory.getLogger(ReviewQueryService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviews;
    private final UserInternalClient users;

    public ReviewQueryService(ReviewRepository reviews, UserInternalClient users) {
        this.reviews = reviews;
        this.users = users;
    }

    public ReviewView get(String id) {
        Review review = reviews.findById(id).orElseThrow(() -> new ReviewNotFoundException(id));
        return ReviewView.from(review, usernames(List.of(review)).get(review.getBuyerSub()));
    }

    public ReviewPage bySeller(String sellerSub, String cursor, Integer requestedSize, String listingId) {
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidReviewCursorException();
        }
        Instant beforeCreatedAt = null;
        String beforeId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = decode(cursor);
            beforeCreatedAt = Instant.parse(parts[0]);
            beforeId = parts[1];
        }
        List<Review> rows = reviews.bySeller(sellerSub, listingId, beforeCreatedAt, beforeId,
                PageRequest.of(0, size + 1));
        // The extra row proves a continuation exists without a count query.
        List<Review> page = rows.subList(0, Math.min(rows.size(), size));
        Map<String, String> names = usernames(page);
        List<ReviewView> items = new ArrayList<>(page.size());
        for (Review review : page) {
            items.add(ReviewView.from(review, names.get(review.getBuyerSub())));
        }
        String nextCursor = rows.size() > size ? encode(page.get(page.size() - 1)) : null;
        return new ReviewPage(List.copyOf(items), nextCursor);
    }

    private Map<String, String> usernames(Collection<Review> rows) {
        List<String> subs = rows.stream().map(Review::getBuyerSub).distinct().toList();
        if (subs.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, String> names = new HashMap<>();
            List<UserSummaryResponse> summaries = users.summaries(subs);
            if (summaries != null) {
                for (UserSummaryResponse summary : summaries) {
                    names.putIfAbsent(summary.sub(), summary.username());
                }
            }
            return names;
        } catch (RuntimeException failure) {
            log.warn("Author lookup failed for {} subs: {}", subs.size(), failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    private String[] decode(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidReviewCursorException();
            }
            Instant.parse(parts[0]);
            return parts;
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidReviewCursorException();
        }
    }

    private String encode(Review review) {
        String value = review.getCreatedAt() + "|" + review.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
