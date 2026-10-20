package it.rebyte.catalog.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.PriceChange;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.config.RabbitConfig;
import it.rebyte.catalog.messaging.ListingChangedEvent;

/** Cache-aside cache for the public listing aggregate only, never checkout availability. */
@Component
public class ListingCache {

    private static final Logger log = LoggerFactory.getLogger(ListingCache.class);
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper mapper;
    private final String keyPrefix;
    private final Duration ttl;

    public ListingCache(@Qualifier("redisTemplate") RedisTemplate<String, String> redis, ObjectMapper mapper,
            @Value("${rebyte.catalog.listing-detail-cache.key-prefix:rebyte:catalog:listing-detail:}") String keyPrefix,
            @Value("${rebyte.catalog.listing-detail-cache.ttl:60}") long ttlSeconds) {
        this.redis = redis;
        this.mapper = mapper;
        this.keyPrefix = keyPrefix;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public Listing get(String listingId, Supplier<Listing> loader) {
        String key = key(listingId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return mapper.readValue(cached, CachedListing.class).toListing();
            Listing loaded = loader.get();
            redis.opsForValue().set(key, mapper.writeValueAsString(CachedListing.from(loaded)), ttl);
            return loaded;
        } catch (DataAccessException | JsonProcessingException ex) {
            log.warn("listing detail cache unavailable for listingId={}", listingId, ex);
            return loader.get();
        }
    }

    public void evict(String listingId) {
        try {
            redis.delete(key(listingId));
        } catch (DataAccessException ex) {
            log.warn("could not evict listing detail cache for listingId={}", listingId, ex);
        }
    }

    /** Every listing event reaches this distinct queue, so cache eviction cannot compete with indexing. */
    @RabbitListener(queues = RabbitConfig.LISTING_CACHE_EVICTION_QUEUE)
    public void onListingChanged(Message message) {
        try {
            ListingChangedEvent event = mapper.readValue(message.getBody(), ListingChangedEvent.class);
            evict(event.listingId());
        } catch (IOException ex) {
            throw new IllegalStateException("failed to parse listing cache eviction event", ex);
        }
    }

    private String key(String listingId) { return keyPrefix + listingId; }

    /**
     * Deliberate cache wire format: {@link Listing} is a Mongo aggregate with
     * persistence creators and mutable technical state, not a JSON contract.
     * This snapshot contains only public-detail fields and therefore remains
     * round-trippable with the application's configured {@link ObjectMapper}.
     */
    private record CachedListing(
            String id, String title, String description, BigDecimal price, Instant publication, ListingStatus status,
            List<String> photos, Set<PaymentMethod> paymentMethods, String sellerSub, String categoryId,
            CachedHardware hardware, CachedSoftware software, Map<String, CachedAttribute> attributes,
            List<CachedPriceChange> priceHistory) {

        static CachedListing from(Listing listing) {
            HardwareListing hardware = listing.getHardware();
            SoftwareListing software = listing.getSoftware();
            Map<String, CachedAttribute> attributes = new LinkedHashMap<>();
            listing.getAttributes().forEach((id, value) -> attributes.put(id, CachedAttribute.from(value)));
            return new CachedListing(listing.getId(), listing.getTitle(), listing.getDescription(), listing.getPrice(),
                    listing.getPublication(), listing.getStatus(), List.copyOf(listing.getPhotos()),
                    Set.copyOf(listing.getPaymentMethods()), listing.getSellerSub(), listing.getCategoryId(),
                    hardware == null ? null : CachedHardware.from(hardware),
                    software == null ? null : CachedSoftware.from(software), Map.copyOf(attributes),
                    listing.getPriceHistory().stream().map(CachedPriceChange::from).toList());
        }

        Listing toListing() {
            Map<String, AttributeValue> restoredAttributes = new LinkedHashMap<>();
            attributes.forEach((id, value) -> restoredAttributes.put(id, value.toAttributeValue()));
            return new Listing(id, title, description, price, publication, status, photos, paymentMethods, sellerSub,
                    categoryId, hardware == null ? null : hardware.toHardware(),
                    software == null ? null : software.toSoftware(), restoredAttributes,
                    priceHistory.stream().map(CachedPriceChange::toPriceChange).toList());
        }
    }

    private record CachedHardware(Condition condition, String shippingLocation, BigDecimal shippingCost) {
        static CachedHardware from(HardwareListing hardware) {
            return new CachedHardware(hardware.getCondition(), hardware.getShippingLocation(), hardware.getShippingCost());
        }

        HardwareListing toHardware() { return new HardwareListing(condition, shippingLocation, shippingCost); }
    }

    private record CachedSoftware(Set<Platform> platforms, List<CachedVersion> versions, String licenceKeyPattern) {
        static CachedSoftware from(SoftwareListing software) {
            return new CachedSoftware(Set.copyOf(software.getPlatforms()),
                    software.getVersions().stream().map(CachedVersion::from).toList(), software.getLicenceKeyPattern());
        }

        SoftwareListing toSoftware() {
            return new SoftwareListing(platforms, versions.stream().map(CachedVersion::toVersion).toList(), licenceKeyPattern);
        }
    }

    private record CachedVersion(String number, Instant release, String notes, String artifactKey) {
        static CachedVersion from(Version version) {
            return new CachedVersion(version.getNumber(), version.getRelease(), version.getNotes(), version.getArtifactKey());
        }

        Version toVersion() { return new Version(number, release, notes, artifactKey); }
    }

    private record CachedAttribute(AttributeType type, String stringValue, Long integerValue,
            BigDecimal decimalValue, Boolean booleanValue) {
        static CachedAttribute from(AttributeValue value) {
            return new CachedAttribute(value.getType(), value.getStringValue(), value.getIntegerValue(),
                    value.getDecimalValue(), value.getBooleanValue());
        }

        AttributeValue toAttributeValue() {
            return new AttributeValue(type, stringValue, integerValue, decimalValue, booleanValue);
        }
    }

    private record CachedPriceChange(String id, Instant instant, BigDecimal oldPrice, BigDecimal newPrice) {
        static CachedPriceChange from(PriceChange change) {
            return new CachedPriceChange(change.getId(), change.getInstant(), change.getOldPrice(), change.getNewPrice());
        }

        PriceChange toPriceChange() { return new PriceChange(id, instant, oldPrice, newPrice); }
    }
}
