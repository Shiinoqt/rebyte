package it.rebyte.catalog.search;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.SwapIndexesParams;

import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.dto.RebuildStatusResponse;
import it.rebyte.catalog.messaging.ListingChangedEvent;
import it.rebyte.catalog.outbox.OutboxEvent;
import it.rebyte.catalog.outbox.OutboxRepository;
import it.rebyte.catalog.repository.ListingRepository;
import it.rebyte.commons.messaging.RebyteExchanges;

/** Builds a complete replacement index and atomically swaps it into the live UID. */
@Component
public class IndexRebuildJob {

    private static final Set<String> LISTING_ROUTING_KEYS = Set.of(
            RebyteExchanges.LISTING_CREATED,
            RebyteExchanges.LISTING_UPDATED,
            RebyteExchanges.LISTING_REMOVED);

    private final Client client;
    private final ListingRepository listings;
    private final OutboxRepository outbox;
    private final ListingDocumentAssembler assembler;
    private final ListingIndexer indexer;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String error;

    public IndexRebuildJob(Client client, ListingRepository listings, OutboxRepository outbox,
            ListingDocumentAssembler assembler, ListingIndexer indexer, ObjectMapper objectMapper,
            @Value("${rebyte.search.rebuild.batch-size:250}") int batchSize) {
        this.client = client;
        this.listings = listings;
        this.outbox = outbox;
        this.assembler = assembler;
        this.indexer = indexer;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    /** Starts one background rebuild. False means another rebuild already owns the slot. */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        CompletableFuture.runAsync(this::rebuildOwned);
        return true;
    }

    /** Synchronous entry point used by scheduled work and integration tests. */
    public void rebuild() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("an index rebuild is already running");
        }
        rebuildOwned();
        if (error != null) {
            throw new IllegalStateException("index rebuild failed: " + error);
        }
    }

    public RebuildStatusResponse status() {
        return new RebuildStatusResponse(running.get(), startedAt, completedAt, error);
    }

    private void rebuildOwned() {
        String replacementUid = MeilisearchConfig.LISTINGS_INDEX + "_rebuild_"
                + UUID.randomUUID().toString().replace("-", "");
        startedAt = Instant.now();
        completedAt = null;
        error = null;
        boolean created = false;
        try {
            var createTask = client.createIndex(replacementUid, "id");
            client.waitForTask(createTask.getTaskUid());
            created = true;
            Index replacement = client.index(replacementUid);

            var settingsTask = replacement.updateSettings(ListingIndexSettings.buildSettings());
            replacement.waitForTask(settingsTask.getTaskUid());
            populate(replacement);

            SwapIndexesParams swap = new SwapIndexesParams()
                    .setIndexes(new String[] {MeilisearchConfig.LISTINGS_INDEX, replacementUid});
            var swapTask = client.swapIndexes(new SwapIndexesParams[] {swap});
            client.waitForTask(swapTask.getTaskUid());

            replayEventsSince(startedAt, client.index(MeilisearchConfig.LISTINGS_INDEX));
            var deleteTask = client.deleteIndex(replacementUid);
            client.waitForTask(deleteTask.getTaskUid());
            created = false;
            completedAt = Instant.now();
        } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (created) {
                deleteQuietly(replacementUid);
            }
        } finally {
            running.set(false);
        }
    }

    private void populate(Index replacement) throws MeilisearchException, JsonProcessingException {
        int pageNumber = 0;
        while (true) {
            var page = listings.findAll(PageRequest.of(pageNumber, batchSize, Sort.by("id")));
            List<ListingDocument> documents = new ArrayList<>();
            for (Listing listing : page.getContent()) {
                if (listing.getStatus() != ListingStatus.REMOVED) {
                    long sequence = listing.getVersion() == null ? 0L : listing.getVersion();
                    documents.add(assembler.assemble(listing, sequence));
                }
            }
            if (!documents.isEmpty()) {
                var task = replacement.addDocuments(objectMapper.writeValueAsString(documents));
                replacement.waitForTask(task.getTaskUid());
            }
            if (!page.hasNext()) {
                return;
            }
            pageNumber++;
        }
    }

    private void replayEventsSince(Instant start, Index liveIndex) throws JsonProcessingException {
        List<OutboxEvent> events = outbox.findByOccurredAtGreaterThanEqualOrderByOccurredAtAsc(start);
        for (OutboxEvent event : events) {
            if (!LISTING_ROUTING_KEYS.contains(event.getRoutingKey())) {
                continue;
            }
            ListingChangedEvent changed = objectMapper.readValue(event.getPayload(), ListingChangedEvent.class);
            indexer.apply(changed, event.getRoutingKey(), liveIndex);
        }
    }

    private void deleteQuietly(String uid) {
        try {
            var task = client.deleteIndex(uid);
            client.waitForTask(task.getTaskUid());
        } catch (MeilisearchException ignored) {
            // The original rebuild error remains the status reported to the operator.
        }
    }
}
