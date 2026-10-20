package it.rebyte.catalog.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Config;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.exceptions.MeilisearchException;

/**
 * Wires the Meilisearch Java client (0.21.0) and a handle to the
 * {@code listings} index. {@code rebyte.search.master-key} carries no
 * inline default (T-02-01-03): a deployment missing it must refuse to
 * start rather than talk to an unauthenticated index.
 *
 * <p>{@link Client#index(String)} builds a LOCAL reference without an HTTP
 * call (verified against the client's own {@code _autodocs}, this session)
 * — safe to call from a {@code @Bean} method with no network I/O at context
 * startup. {@link ListingIndexSettings} is what actually creates the index
 * and applies its settings, at {@code ApplicationRunner} time.
 */
@Configuration
public class MeilisearchConfig {

    /** The one search index this phase projects listings into. */
    public static final String LISTINGS_INDEX = "listings";

    @Bean
    Client meilisearchClient(
            @Value("${rebyte.search.url}") String url,
            @Value("${rebyte.search.master-key}") String masterKey) {
        return new Client(new Config(url, masterKey));
    }

    @Bean
    Index listingsIndex(Client meilisearchClient) throws MeilisearchException {
        return meilisearchClient.index(LISTINGS_INDEX);
    }
}
