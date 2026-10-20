package it.rebyte.catalog.search;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.DocumentsQuery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.rebyte.catalog.domain.ListingStatus;
import it.rebyte.catalog.repository.ListingRepository;

/** Reports source/index divergence without repairing or concealing it. */
@Component
public class IndexDriftCheckJob {

    private static final Logger log = LoggerFactory.getLogger(IndexDriftCheckJob.class);
    private static final int PAGE_SIZE = 1000;

    private final ListingRepository listings;
    private final Index index;
    private final ObjectMapper objectMapper;
    private final AtomicInteger driftGauge = new AtomicInteger();
    private volatile DriftReport lastReport = DriftReport.empty();

    public IndexDriftCheckJob(ListingRepository listings, Index index,
            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.listings = listings;
        this.index = index;
        this.objectMapper = objectMapper;
        Gauge.builder("rebyte.search.index.drift", driftGauge, AtomicInteger::get)
                .description("Active listing/index drift entries")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${rebyte.search.drift.fixed-delay-ms:300000}",
            initialDelayString = "${rebyte.search.drift.initial-delay-ms:300000}")
    public DriftReport check() {
        Map<String, Long> source = new LinkedHashMap<>();
        listings.findAll().stream()
                .filter(listing -> listing.getStatus() == ListingStatus.ACTIVE)
                .forEach(listing -> source.put(listing.getId(), listing.getVersion() == null ? 0L : listing.getVersion()));
        Map<String, Long> indexed = readActiveIndexDocuments();

        Set<String> missingFromIndex = new LinkedHashSet<>(source.keySet());
        missingFromIndex.removeAll(indexed.keySet());
        Set<String> missingFromSource = new LinkedHashSet<>(indexed.keySet());
        missingFromSource.removeAll(source.keySet());
        Map<String, SequenceLag> stale = new LinkedHashMap<>();
        source.forEach((id, sourceSequence) -> {
            Long indexedSequence = indexed.get(id);
            if (indexedSequence != null && indexedSequence < sourceSequence) {
                stale.put(id, new SequenceLag(sourceSequence, indexedSequence));
            }
        });

        lastReport = new DriftReport(Set.copyOf(missingFromIndex), Set.copyOf(missingFromSource), Map.copyOf(stale));
        driftGauge.set(lastReport.total());
        log.info("INDEX_DRIFT missingFromIndex={} missingFromSource={} stale={} total={}",
                missingFromIndex.size(), missingFromSource.size(), stale.size(), lastReport.total());
        return lastReport;
    }

    public DriftReport lastReport() {
        return lastReport;
    }

    public int driftCount() {
        return check().total();
    }

    private Map<String, Long> readActiveIndexDocuments() {
        Map<String, Long> result = new LinkedHashMap<>();
        int offset = 0;
        try {
            while (true) {
                DocumentsQuery query = new DocumentsQuery()
                        .setOffset(offset)
                        .setLimit(PAGE_SIZE)
                        .setFields(new String[] {"id", "status", "seq"});
                JsonNode response = objectMapper.readTree(index.getRawDocuments(query));
                JsonNode documents = response.path("results");
                for (JsonNode document : documents) {
                    if (ListingStatus.ACTIVE.name().equals(document.path("status").asText())) {
                        result.put(document.path("id").asText(), document.path("seq").asLong());
                    }
                }
                offset += documents.size();
                if (documents.size() < PAGE_SIZE || offset >= response.path("total").asInt()) {
                    return result;
                }
            }
        } catch (MeilisearchException | java.io.IOException e) {
            throw new IllegalStateException("index drift check failed", e);
        }
    }

    public record DriftReport(
            Set<String> missingFromIndex,
            Set<String> missingFromSource,
            Map<String, SequenceLag> staleDocuments) {

        static DriftReport empty() {
            return new DriftReport(Set.of(), Set.of(), Map.of());
        }

        public int total() {
            return missingFromIndex.size() + missingFromSource.size() + staleDocuments.size();
        }
    }

    public record SequenceLag(long sourceSequence, long indexedSequence) {
    }
}
