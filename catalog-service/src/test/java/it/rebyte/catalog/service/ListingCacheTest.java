package it.rebyte.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.amqp.core.Message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.HardwareListing;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;
import it.rebyte.catalog.domain.SoftwareListing;
import it.rebyte.catalog.domain.Version;
import it.rebyte.catalog.messaging.ListingChangedEvent;

class ListingCacheTest {

    @Test
    void roundTripsARealListingBeforeServingTheSecondDetailRead() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(ignored -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        ListingCache cache = new ListingCache(redis, new ObjectMapper().registerModule(new JavaTimeModule()), "detail:", 60);
        Listing listing = new Listing("listing-1", "Title", "Description", new BigDecimal("10.00"), Instant.now(),
                ListingStatus.ACTIVE, List.of("photo-1"), Set.of(PaymentMethod.PAYPAL), "seller", "category",
                new HardwareListing(Condition.GOOD, "Rome", BigDecimal.ZERO), null, java.util.Map.of());

        assertThat(cache.get(listing.getId(), () -> listing)).isSameAs(listing);
        Listing restored = cache.get(listing.getId(), () -> { throw new AssertionError("cache miss"); });

        assertThat(restored.getId()).isEqualTo(listing.getId());
        assertThat(restored.getPrice()).isEqualByComparingTo("10.00");
        assertThat(restored.getHardware().getShippingLocation()).isEqualTo("Rome");
    }

    @Test
    void roundTripsTheStoredSoftwareLicencePattern() {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicReference<String> stored = new AtomicReference<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(ignored -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));

        ListingCache cache = new ListingCache(redis, new ObjectMapper().registerModule(new JavaTimeModule()), "detail:", 60);
        Version version = new Version("1.0.0", Instant.now(), "Initial", "software/key");
        String pattern = "ACME-########-########";
        Listing listing = new Listing("software-listing", "Software", "Description", new BigDecimal("10.00"), Instant.now(),
                ListingStatus.ACTIVE, List.of(), Set.of(PaymentMethod.PAYPAL), "seller", "category", null,
                new SoftwareListing(Set.of(Platform.LINUX), List.of(version), pattern), Map.of());

        cache.get(listing.getId(), () -> listing);
        Listing restored = cache.get(listing.getId(), () -> { throw new AssertionError("cache miss"); });

        assertThat(restored.getSoftware().getLicenceKeyPattern()).isEqualTo(pattern);
    }

    @Test
    void evictsByListingIdForEveryMessageOnTheCommonListingEventQueue() throws Exception {
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ListingCache cache = new ListingCache(redis, mapper, "detail:", 60);
        Message message = new Message(mapper.writeValueAsBytes(
                new ListingChangedEvent("listing-1", 4, Instant.now())));

        cache.onListingChanged(message);

        verify(redis).delete("detail:listing-1");
    }
}
