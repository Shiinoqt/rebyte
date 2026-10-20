package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import it.rebyte.messaging.domain.FavouriteProjection;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.messaging.ListingPriceEvent;
import it.rebyte.messaging.service.NotificationPushes;
import it.rebyte.messaging.service.NotificationWriter;
import it.rebyte.messaging.service.PriceDropService;

class PriceDropServiceTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final NotificationWriter writer = mock(NotificationWriter.class);
    private final NotificationPushes pushes = mock(NotificationPushes.class);
    private final PriceDropService service = new PriceDropService(mongo, writer, pushes);

    static Stream<Arguments> nonDrops() {
        return Stream.of(Arguments.of(null, "9.99"), Arguments.of("10.00", null),
                Arguments.of(null, null), Arguments.of("10.0", "10.00"), Arguments.of("10.00", "10.01"));
    }

    @ParameterizedTest
    @MethodSource("nonDrops")
    void nonDropsReturnBeforeReadingOrWriting(String oldPrice, String newPrice) {
        assertThat(service.fanOut(event(oldPrice, newPrice))).isZero();
        verifyNoInteractions(mongo, writer, pushes);
    }

    @Test
    void oneCentDecreaseBuildsTheSnapshotAndPushesInsertedRows() {
        Instant time = Instant.parse("2026-10-05T10:15:30.123456789Z");
        FavouriteProjection favourite = new FavouriteProjection("alice|L", "alice", "L", "seller", true,
                time, time.toString(), "event", 1L);
        when(mongo.find(any(Query.class), eq(FavouriteProjection.class))).thenReturn(List.of(favourite));
        when(writer.insertAllIgnoringDuplicates(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.fanOut(event("10.00", "9.99"))).isEqualTo(1);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(FavouriteProjection.class));
        assertThat(query.getValue().getQueryObject()).containsEntry("listingId", "L").containsEntry("active", true);
        assertThat(query.getValue().getSortObject()).containsEntry("userSub", 1);
        assertThat(query.getValue().getLimit()).isEqualTo(500);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> rows = ArgumentCaptor.forClass(List.class);
        verify(writer).insertAllIgnoringDuplicates(rows.capture());
        Notification stored = rows.getValue().getFirst();
        assertThat(stored.getRecipientSub()).isEqualTo("alice");
        assertThat(stored.getPriceDrop().instant()).isEqualTo(Instant.parse("2026-10-05T10:15:30.123Z"));
        assertThat(stored.getSnapshot()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "listingTitle", "Sony WH-1000XM4", "oldPrice", "10.00", "newPrice", "9.99"));
        verify(pushes).created(rows.getValue());
    }

    private ListingPriceEvent event(String oldPrice, String newPrice) {
        return new ListingPriceEvent("L", 1, Instant.parse("2026-10-05T10:15:30.123456789Z"),
                oldPrice == null ? null : new BigDecimal(oldPrice), newPrice == null ? null : new BigDecimal(newPrice),
                "Sony WH-1000XM4");
    }
}
