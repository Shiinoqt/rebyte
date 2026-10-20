package it.rebyte.catalog.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.dto.ListingCard;
import it.rebyte.catalog.dto.ListingCardPage;
import it.rebyte.catalog.error.InvalidListingCursorException;

@Service
public class ListingCardQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final MongoTemplate mongo;
    private final PhotoUrls photoUrls;

    public ListingCardQueryService(MongoTemplate mongo, PhotoUrls photoUrls) {
        this.mongo = mongo;
        this.photoUrls = photoUrls;
    }

    public ListingCardPage mine(String sellerSub, ListingStatus status, String cursor, Integer size) {
        return page(sellerSub, status, cursor, size);
    }

    public ListingCardPage activeBySeller(String sellerSub, String cursor, Integer size) {
        return page(sellerSub, ListingStatus.ACTIVE, cursor, size);
    }

    private ListingCardPage page(String sellerSub, ListingStatus status, String cursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidListingCursorException();
        }
        Criteria criteria = Criteria.where("sellerSub").is(sellerSub);
        if (status != null) {
            criteria = criteria.and("status").is(status);
        }
        if (cursor != null && !cursor.isBlank()) {
            Position after = decode(cursor);
            criteria = criteria.andOperator(new Criteria().orOperator(
                    Criteria.where("publication").lt(after.publication()),
                    Criteria.where("publication").is(after.publication()).and("id").lt(after.id())));
        }
        // One extra row proves a further page exists without a count query.
        Query query = new Query(criteria)
                .with(Sort.by(Sort.Order.desc("publication"), Sort.Order.desc("id")))
                .limit(size + 1);
        List<Listing> rows = mongo.find(query, Listing.class);

        int limit = Math.min(rows.size(), size);
        List<ListingCard> items = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            items.add(card(rows.get(index)));
        }
        String nextCursor = rows.size() > limit ? encode(rows.get(limit - 1)) : null;
        return new ListingCardPage(List.copyOf(items), nextCursor);
    }

    private ListingCard card(Listing listing) {
        List<String> photos = listing.getPhotos();
        String coverUrl = photos == null || photos.isEmpty() ? null : photoUrls.toUrl(photos.get(0));
        ListingType type = listing.type();
        if (type == ListingType.HARDWARE) {
            return new ListingCard(listing.getId(), listing.getTitle(), listing.getPrice(), type, listing.getStatus(),
                    coverUrl, listing.getHardware().getCondition(), listing.getHardware().getShippingLocation(),
                    null, null, listing.getPublication());
        }
        var software = listing.getSoftware();
        List<Platform> platforms = software.getPlatforms().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        String currentVersion = software.getVersions().isEmpty() ? null : software.currentVersion().getNumber();
        return new ListingCard(listing.getId(), listing.getTitle(), listing.getPrice(), type, listing.getStatus(),
                coverUrl, null, null, platforms, currentVersion, listing.getPublication());
    }

    private Position decode(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidListingCursorException();
            }
            return new Position(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidListingCursorException();
        }
    }

    private String encode(Listing listing) {
        String value = listing.getPublication() + "|" + listing.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Position(Instant publication, String id) {
    }
}
