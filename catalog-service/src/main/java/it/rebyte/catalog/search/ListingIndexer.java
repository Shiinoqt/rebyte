package it.rebyte.catalog.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchApiException;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.TaskInfo;
import com.rabbitmq.client.Channel;

import it.rebyte.catalog.config.RabbitConfig;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Projects bounded broker batches, coalescing each listing to its highest sequence.
 * Deliveries remain unacknowledged until projection succeeds, so closing the
 * consumer channel returns in-flight work to RabbitMQ. Rebuilds use apply directly.
 */
@Component
public class ListingIndexer {

    private static final Logger log = LoggerFactory.getLogger(ListingIndexer.class);
    private static final String DOCUMENT_NOT_FOUND = "document_not_found";

    private final Index listingsIndex;
    private final ObjectMapper objectMapper;
    private final ListingRepository listingRepository;
    private final ListingDocumentAssembler assembler;

    public ListingIndexer(
            Index listingsIndex,
            ObjectMapper objectMapper,
            ListingRepository listingRepository,
            ListingDocumentAssembler assembler) {
        this.listingsIndex = listingsIndex;
        this.objectMapper = objectMapper;
        this.listingRepository = listingRepository;
        this.assembler = assembler;
    }

    @RabbitListener(queues = RabbitConfig.LISTING_CHANGED_QUEUE,
            containerFactory = "listingIndexerContainerFactory")
    public void onListingChanged(List<Message> messages, Channel channel) throws IOException {
        Map<String, PendingUpdate> batch = new LinkedHashMap<>();
        for (Message message : messages) {
            ListingChangedEvent event;
            try {
                event = objectMapper.readValue(message.getBody(), ListingChangedEvent.class);
                if (event == null || event.listingId() == null || event.listingId().isBlank() || event.seq() < 0) {
                    throw new IllegalArgumentException("invalid listing projection event");
                }
            } catch (IOException | IllegalArgumentException e) {
                log.error("rejecting malformed listing projection event");
                channel.basicReject(message.getMessageProperties().getDeliveryTag(), false);
                continue;
            }
            PendingUpdate existing = batch.get(event.listingId());
            List<Long> tags = existing == null ? new ArrayList<>() : existing.deliveryTags();
            tags.add(message.getMessageProperties().getDeliveryTag());
            if (existing == null || event.seq() > existing.event().seq()) {
                batch.put(event.listingId(), new PendingUpdate(event,
                        message.getMessageProperties().getReceivedRoutingKey(), tags));
            }
        }
        List<Long> failedTags = new ArrayList<>();
        for (PendingUpdate update : batch.values()) {
            try {
                apply(update.event(), update.routingKey(), listingsIndex);
            } catch (RuntimeException e) {
                log.error("failed to project listingId={} seq={}; requeuing deliveries",
                        update.event().listingId(), update.event().seq(), e);
                failedTags.addAll(update.deliveryTags());
                continue;
            }
            for (long tag : update.deliveryTags()) {
                channel.basicAck(tag, false);
            }
        }
        if (!failedTags.isEmpty()) {
            // Pace broker redelivery during outages without acknowledging failed projections.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (long tag : failedTags) {
                channel.basicNack(tag, false, true);
            }
        }
    }

    /** Applies a live or rebuild-replayed event to the supplied target index. */
    public synchronized void apply(ListingChangedEvent event, String routingKey, Index targetIndex) {
        Long storedSeq = readStoredSeq(targetIndex, event.listingId());
        if (storedSeq != null && storedSeq >= event.seq()) {
            log.info("DISCARDED_STALE listingId={} incomingSeq={} storedSeq={}",
                    event.listingId(), event.seq(), storedSeq);
            return;
        }
        if (RebyteExchanges.LISTING_REMOVED.equals(routingKey)) {
            deleteFromIndex(targetIndex, event);
            return;
        }
        upsert(targetIndex, event);
    }

    private void upsert(Index targetIndex, ListingChangedEvent event) {
        Listing listing = listingRepository.findById(event.listingId())
                .orElseThrow(() -> new IllegalStateException("listing " + event.listingId() + " not found for indexing"));
        ListingDocument document = assembler.assemble(listing, event.seq());

        try {
            String json = objectMapper.writeValueAsString(document);
            TaskInfo task = targetIndex.addDocuments(json);
            targetIndex.waitForTask(task.getTaskUid());
            log.info("UPSERTED listingId={} seq={} task={}", event.listingId(), event.seq(), task.getTaskUid());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize listing document " + event.listingId(), e);
        } catch (MeilisearchException e) {
            throw new IllegalStateException("failed to index listing " + event.listingId(), e);
        }
    }

    private void deleteFromIndex(Index targetIndex, ListingChangedEvent event) {
        try {
            TaskInfo task = targetIndex.deleteDocument(event.listingId());
            targetIndex.waitForTask(task.getTaskUid());
            log.info("DELETED listingId={} task={}", event.listingId(), task.getTaskUid());
        } catch (MeilisearchException e) {
            throw new IllegalStateException("failed to delete listing " + event.listingId() + " from search index", e);
        }
    }

    /**
     * {@code null} means "no stored document" — a first write is always
     * greater than nothing.
     *
     * <p>Deliberately uses {@link Index#getRawDocument(String)} plus this
     * service's own Jackson {@link ObjectMapper}, NOT the typed {@code
     * Index#getDocument(String, Class)} overload. The SDK's typed overload
     * decodes the response body with its bundled Gson instance, which has
     * no built-in adapter for {@code java.time.Instant} and falls back to
     * reflection on {@code Instant}'s private fields — blocked outright
     * under Java 26's module encapsulation ({@code InaccessibleObjectException}:
     * module {@code java.base} does not open {@code java.time}). Reading the
     * raw JSON and extracting only {@code seq} with the Spring-managed mapper
     * avoids that incompatible typed deserialization path.
     */
    private Long readStoredSeq(Index targetIndex, String listingId) {
        try {
            String rawDocument = targetIndex.getRawDocument(listingId);
            JsonNode node = objectMapper.readTree(rawDocument);
            return node.hasNonNull("seq") ? node.get("seq").asLong() : null;
        } catch (MeilisearchApiException e) {
            if (DOCUMENT_NOT_FOUND.equals(e.getCode())) {
                return null;
            }
            throw new IllegalStateException("failed to read stored listing document " + listingId, e);
        } catch (MeilisearchException e) {
            throw new IllegalStateException("failed to read stored listing document " + listingId, e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse stored listing document " + listingId, e);
        }
    }

    private record PendingUpdate(ListingChangedEvent event, String routingKey, List<Long> deliveryTags) {
    }
}
