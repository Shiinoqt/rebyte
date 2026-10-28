package it.rebyte.catalog.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchApiException;
import com.meilisearch.sdk.exceptions.MeilisearchException;
import com.meilisearch.sdk.model.FilterableAttributesConfig;
import com.meilisearch.sdk.model.FilterableAttributesFeatures;
import com.meilisearch.sdk.model.FilterableAttributesFilter;
import com.meilisearch.sdk.model.Settings;
import com.meilisearch.sdk.model.TaskInfo;

/**
 * Bootstraps the {@code listings} index and its settings on startup,
 * idempotently — an identical settings write is a no-op server-side, so
 * every service restart is safe to re-run this.
 *
 * <p>The filter configuration uses one equality-only wildcard baseline and
 * a named numeric override for fields that also support comparison. New
 * equality attributes work without a settings change; numeric range fields
 * must be added to the override.
 *
 * <p>{@link #buildSettings()} is shared by startup and replacement-index
 * rebuilds so both paths apply identical settings.
 *
 * <p>{@code rebyte.search.bootstrap-enabled} controls the startup network
 * call. Tests without Meilisearch disable it; deployments and infrastructure
 * tests keep it enabled.
 */
@Component
@ConditionalOnProperty(prefix = "rebyte.search", name = "bootstrap-enabled", havingValue = "true", matchIfMissing = true)
public class ListingIndexSettings implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ListingIndexSettings.class);
    private static final String INDEX_NOT_FOUND = "index_not_found";
    private static final String PRIMARY_KEY = "id";

    private final Client client;
    private final Index listingsIndex;

    public ListingIndexSettings(Client client, Index listingsIndex) {
        this.client = client;
        this.listingsIndex = listingsIndex;
    }

    @Override
    public void run(ApplicationArguments args) throws MeilisearchException {
        ensureIndexExists();
        TaskInfo task = listingsIndex.updateSettings(buildSettings());
        listingsIndex.waitForTask(task.getTaskUid());
        log.info("listings index settings applied (task {})", task.getTaskUid());
    }

    private void ensureIndexExists() throws MeilisearchException {
        try {
            client.getIndex(MeilisearchConfig.LISTINGS_INDEX);
        } catch (MeilisearchApiException e) {
            if (!INDEX_NOT_FOUND.equals(e.getCode())) {
                throw e;
            }
            TaskInfo created = client.createIndex(MeilisearchConfig.LISTINGS_INDEX, PRIMARY_KEY);
            listingsIndex.waitForTask(created.getTaskUid());
            log.info("listings index created (task {})", created.getTaskUid());
        }
    }

    /** D-09's granular filterableAttributes shape — the wildcard-plus-overrides settings object. */
    public static Settings buildSettings() {
        FilterableAttributesFilter equalityOnly = new FilterableAttributesFilter();
        equalityOnly.setEquality(true);
        equalityOnly.setComparison(false);

        FilterableAttributesFeatures wildcardFeatures = new FilterableAttributesFeatures();
        wildcardFeatures.setFacetSearch(false);
        wildcardFeatures.setFilter(equalityOnly);

        FilterableAttributesConfig wildcard = new FilterableAttributesConfig();
        wildcard.setAttributePatterns(new String[] {"*"});
        wildcard.setFeatures(wildcardFeatures);

        FilterableAttributesFilter equalityAndComparison = new FilterableAttributesFilter();
        equalityAndComparison.setEquality(true);
        equalityAndComparison.setComparison(true);

        FilterableAttributesFeatures numericFeatures = new FilterableAttributesFeatures();
        numericFeatures.setFacetSearch(false);
        numericFeatures.setFilter(equalityAndComparison);

        FilterableAttributesConfig numericOverride = new FilterableAttributesConfig();
        numericOverride.setAttributePatterns(new String[] {"price", "attr.vram_gb"});
        numericOverride.setFeatures(numericFeatures);

        Settings settings = new Settings();
        // Meilisearch applies the first matching granular rule. Specific
        // numeric fields must therefore precede the catch-all wildcard.
        settings.setFilterableAttributesConfig(new FilterableAttributesConfig[] {numericOverride, wildcard});
        // shortcut: publishedAt is indexed as an ISO-8601 string and Meilisearch sorts strings by bytes, so
        // listings published within the same second can mis-order; upgrade to a numeric epoch-millis field
        // when exact sub-second order matters.
        settings.setSortableAttributes(new String[] {"id", "publishedAt"});
        // A chosen order must beat typo, proximity and attribute; the sort rule is inert without a sort parameter.
        settings.setRankingRules(new String[] {
                "words", "sort", "typo", "proximity", "attribute", "exactness", "id:asc"
        });
        return settings;
    }
}
