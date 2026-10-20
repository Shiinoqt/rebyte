package it.rebyte.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.TaskInfo;
import com.rabbitmq.client.Channel;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.commons.messaging.RebyteExchanges;

@ExtendWith(MockitoExtension.class)
class ListingIndexerTest {

    @Mock
    private Channel channel;

    @Mock
    private Index listingsIndex;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private ListingDocumentAssembler assembler;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void redeliveredEventWithSequenceNotGreaterThanStoredIsDiscardedWithoutTouchingTheIndexOrTheRepository() throws Exception {
        String listingId = "listing-stale-seq";
        when(listingsIndex.getRawDocument(listingId)).thenReturn("{\"id\":\"" + listingId + "\",\"seq\":5}");

        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        Message message = listingChangedMessage(listingId, 3L, RebyteExchanges.LISTING_UPDATED);

        indexer.onListingChanged(List.of(message), channel);

        verify(listingsIndex, never()).addDocuments(anyString());
        verify(listingRepository, never()).findById(any());
    }

    @Test
    void eventWithSequenceGreaterThanStoredIsNotDiscarded() throws Exception {
        String listingId = "listing-fresh-seq";
        when(listingsIndex.getRawDocument(listingId)).thenReturn("{\"id\":\"" + listingId + "\",\"seq\":1}");

        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        Message message = listingChangedMessage(listingId, 2L, RebyteExchanges.LISTING_UPDATED);

        indexer.onListingChanged(List.of(message), channel);

        verify(channel).basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        verify(listingRepository).findById(listingId);
    }

    @Test
    void priceChangePayloadHasOneStableOldPriceFieldAndStillReachesTheSequenceGuard() throws Exception {
        String listingId = "listing-price-change";
        when(listingsIndex.getRawDocument(listingId)).thenReturn("{\"id\":\"" + listingId + "\",\"seq\":5}");
        String payload = objectMapper.writeValueAsString(new it.rebyte.catalog.messaging.ListingChangedEvent(
                listingId, 3, java.time.Instant.now(), new java.math.BigDecimal("12.00"), new java.math.BigDecimal("10.00")));
        assertThat(objectMapper.readTree(payload).has("oldPrice")).isTrue();
        assertThat(objectMapper.readTree(payload).has("newPrice")).isTrue();
        assertThat(objectMapper.readTree(payload).has("previousPrice")).isFalse();
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(RebyteExchanges.LISTING_UPDATED);

        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        indexer.onListingChanged(List.of(new Message(payload.getBytes(StandardCharsets.UTF_8), properties)), channel);

        verify(listingsIndex, never()).addDocuments(anyString());
        verify(listingRepository, never()).findById(any());
    }

    @Test
    void burstOfInterleavedVersionsForOneListingCollapsesToOneRemoteCallWithTheHighestSeqIncludingReleaseToActive()
            throws Exception {
        String listingId = "listing-burst";
        when(listingsIndex.getRawDocument(listingId)).thenReturn("{\"id\":\"" + listingId + "\",\"seq\":0}");

        Listing listing = mock(Listing.class);
        when(listingRepository.findById(listingId)).thenReturn(java.util.Optional.of(listing));
        ListingDocument releasedDocument = new ListingDocument(
                listingId, "RTX 3080", "desc", new java.math.BigDecimal("450.00"),
                ListingType.HARDWARE, ListingStatus.ACTIVE, "cat-1", List.of("cat-1"), List.of("Hardware"),
                "seller-1", null, java.time.Instant.now(), Condition.VERY_GOOD, "Milano", List.of(), null, null, 3L, Map.of());
        when(assembler.assemble(listing, 3L)).thenReturn(releasedDocument);

        TaskInfo addTask = new TaskInfo();
        when(listingsIndex.addDocuments(anyString())).thenReturn(addTask);

        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        indexer.onListingChanged(List.of(
                listingChangedMessage(listingId, 1L, RebyteExchanges.LISTING_UPDATED),
                listingChangedMessage(listingId, 3L, RebyteExchanges.LISTING_UPDATED),
                listingChangedMessage(listingId, 2L, RebyteExchanges.LISTING_UPDATED)), channel);

        verify(channel, times(3)).basicAck(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(false));
        verify(assembler, times(1)).assemble(listing, 3L);
        verify(assembler, never()).assemble(listing, 1L);
        verify(assembler, never()).assemble(listing, 2L);
        verify(listingsIndex, times(1)).addDocuments(anyString());
        verify(listingsIndex, org.mockito.Mockito.atMost(1)).addDocuments(anyString());
    }

    @Test
    void burstEndingInRemovedDeletesRatherThanUpsertsAndNeverRegressesToAnEarlierUpsert() throws Exception {
        String listingId = "listing-burst-removed";
        when(listingsIndex.getRawDocument(listingId)).thenReturn("{\"id\":\"" + listingId + "\",\"seq\":0}");
        TaskInfo deleteTask = new TaskInfo();
        when(listingsIndex.deleteDocument(listingId)).thenReturn(deleteTask);

        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        indexer.onListingChanged(List.of(
                listingChangedMessage(listingId, 1L, RebyteExchanges.LISTING_UPDATED),
                listingChangedMessage(listingId, 2L, RebyteExchanges.LISTING_REMOVED)), channel);

        verify(channel, times(2)).basicAck(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(false));
        verify(listingsIndex, times(1)).deleteDocument(listingId);
        verify(listingsIndex, never()).addDocuments(anyString());
        verify(listingRepository, never()).findById(any());
    }

    @Test
    void transientFailureRequeuesEveryCoalescedDeliveryAndRecoveryAcknowledgesThem() throws Exception {
        when(listingsIndex.getRawDocument("retry")).thenThrow(new IllegalStateException("unavailable"))
                .thenReturn("{\"seq\":3}");
        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        Message first = listingChangedMessage("retry", 1, RebyteExchanges.LISTING_UPDATED);
        Message latest = listingChangedMessage("retry", 3, RebyteExchanges.LISTING_UPDATED);
        List<Message> batch = List.of(first, latest);

        indexer.onListingChanged(batch, channel);
        verify(channel, never()).basicAck(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(channel).basicNack(first.getMessageProperties().getDeliveryTag(), false, true);
        verify(channel).basicNack(latest.getMessageProperties().getDeliveryTag(), false, true);

        indexer.onListingChanged(batch, channel);
        verify(channel).basicAck(first.getMessageProperties().getDeliveryTag(), false);
        verify(channel).basicAck(latest.getMessageProperties().getDeliveryTag(), false);
    }

    @Test
    void failedListingDoesNotRequeueOrLoseSuccessfulListingInSameBatch() throws Exception {
        when(listingsIndex.getRawDocument("failed")).thenThrow(new IllegalStateException("unavailable"));
        when(listingsIndex.getRawDocument("success")).thenReturn("{\"seq\":3}");
        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        Message failed = listingChangedMessage("failed", 1, RebyteExchanges.LISTING_UPDATED);
        Message success = listingChangedMessage("success", 1, RebyteExchanges.LISTING_UPDATED);

        indexer.onListingChanged(List.of(failed, success), channel);

        verify(channel).basicAck(success.getMessageProperties().getDeliveryTag(), false);
        verify(channel).basicNack(failed.getMessageProperties().getDeliveryTag(), false, true);
        verify(channel, never()).basicAck(failed.getMessageProperties().getDeliveryTag(), false);
        verify(channel, never()).basicNack(success.getMessageProperties().getDeliveryTag(), false, true);
    }

    @Test
    void malformedAndInvalidEventsAreRejectedWithoutRequeue() throws Exception {
        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        Message malformed = new Message("{bad".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        Message invalid = listingChangedMessage("", -1, RebyteExchanges.LISTING_UPDATED);
        malformed.getMessageProperties().setDeliveryTag(100);

        indexer.onListingChanged(List.of(malformed, invalid), channel);

        verify(channel).basicReject(100, false);
        verify(channel).basicReject(invalid.getMessageProperties().getDeliveryTag(), false);
        org.mockito.Mockito.verifyNoInteractions(listingsIndex, listingRepository);
    }

    @Test
    void acknowledgementFailureEscapesSoContainerCanRecoverRemainingDeliveries() throws Exception {
        when(listingsIndex.getRawDocument("ack-failed")).thenReturn("{\"seq\":3}");
        Message message = listingChangedMessage("ack-failed", 1, RebyteExchanges.LISTING_UPDATED);
        org.mockito.Mockito.doThrow(new java.io.IOException("channel closed")).when(channel)
                .basicAck(message.getMessageProperties().getDeliveryTag(), false);
        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> indexer.onListingChanged(List.of(message), channel))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void interruptedRetryPausePreservesInterruptAndRequeuesFailedDelivery() throws Exception {
        when(listingsIndex.getRawDocument("interrupted")).thenThrow(new IllegalStateException("unavailable"));
        Message message = listingChangedMessage("interrupted", 1, RebyteExchanges.LISTING_UPDATED);
        ListingIndexer indexer = new ListingIndexer(listingsIndex, objectMapper, listingRepository, assembler);
        Thread.currentThread().interrupt();
        try {
            indexer.onListingChanged(List.of(message), channel);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(channel).basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        } finally {
            Thread.interrupted();
        }
    }

    private long nextDeliveryTag;

    private Message listingChangedMessage(String listingId, long seq, String routingKey) throws Exception {
        String payload = objectMapper.writeValueAsString(
                new it.rebyte.catalog.messaging.ListingChangedEvent(listingId, seq, java.time.Instant.now()));
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        properties.setDeliveryTag(++nextDeliveryTag);
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}
