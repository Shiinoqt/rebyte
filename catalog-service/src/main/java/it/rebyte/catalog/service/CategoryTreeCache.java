package it.rebyte.catalog.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.repository.CategoryRepository;

/**
 * Caches the WHOLE category tree — structure plus attribute definitions,
 * NEVER {@code listingCount} — as one Redis value under one key with a
 * long TTL, evicted on any category or attribute-definition write
 * (02-RESEARCH.md Don't Hand-Roll: "Category-tree traversal caching"). A
 * per-node cache is the wrong shape here: inheritance walks upward (D-01)
 * while invalidation would have to walk downward, so changing an
 * ancestor's definitions would have to evict every descendant. The tree is
 * small and almost never written, so caching it whole removes the cascade
 * entirely.
 *
 * <p><b>{@code listingCount} is deliberately excluded from the cached
 * shape.</b> It changes on every publish and would evict the tree
 * constantly, and it is only ever read inside a transaction as a
 * conditional-update predicate (T-02-02-02) — reading a cache there would
 * be wrong in any case, the same rule docs/architecture.md §6 states for
 * the checkout read path. {@link CategoryService}'s and {@code
 * ListingPublishService}'s leaf-guard conditional updates read {@link
 * CategoryRepository} / {@code MongoTemplate} directly, never this cache.
 *
 * <p><b>Callers evict, this class never subscribes to anything.</b> {@link
 * CategoryService#create} and {@code AttributeDefinitionService#define} /
 * {@code #retireAllowedValue} call {@link #evict()} themselves once their
 * write has committed — no event, no listener, because a same-process
 * direct call is the simplest mechanism that satisfies the requirement
 * (CLAUDE.md: simplicity over abstraction). Publishing a listing never
 * calls this method at all, which is exactly why the cached tree survives
 * ordinary publish traffic unevicted.
 *
 * <p>Stored as plain JSON text via this module's Jackson {@link
 * ObjectMapper}, not JDK serialization — {@link
 * it.rebyte.catalog.config.RedisConfig} configures the key/value
 * serializers so {@code redis-cli GET} returns readable text, matching the
 * manual serialize/deserialize shape {@code DomainEventRecorder} already
 * uses for outbox payloads.
 */
@Component
public class CategoryTreeCache {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final CategoryRepository categoryRepository;
    private final String cacheKey;
    private final Duration ttl;

    public CategoryTreeCache(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            CategoryRepository categoryRepository,
            @Value("${rebyte.catalog.category-tree.key:rebyte:catalog:category-tree}") String cacheKey,
            @Value("${rebyte.catalog.category-tree.ttl:3600}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.categoryRepository = categoryRepository;
        this.cacheKey = cacheKey;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * Cache-aside read: a hit returns the cached snapshot untouched (D-01's
     * inheritance walk and {@code GET /categories} both read this same
     * snapshot, so a cache hit is byte-identical across callers); a miss
     * loads every {@link Category} from Mongo, projects it into {@link
     * CachedCategory} (dropping {@code listingCount}), stores the JSON and
     * returns it.
     */
    public List<CachedCategory> getTree() {
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return deserialize(cached);
        }
        List<CachedCategory> loaded = loadFromMongo();
        redisTemplate.opsForValue().set(cacheKey, serialize(loaded), ttl);
        return loaded;
    }

    /**
     * Evicts the WHOLE blob — never a per-node patch, per this class's own
     * Javadoc on why a per-node cache is the wrong shape here.
     */
    public void evict() {
        redisTemplate.delete(cacheKey);
    }

    private List<CachedCategory> loadFromMongo() {
        List<CachedCategory> snapshot = new ArrayList<>();
        for (Category category : categoryRepository.findAll()) {
            snapshot.add(CachedCategory.from(category));
        }
        return snapshot;
    }

    private String serialize(List<CachedCategory> tree) {
        try {
            return objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize the category tree cache blob", e);
        }
    }

    private List<CachedCategory> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<CachedCategory>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize the category tree cache blob", e);
        }
    }

    /**
     * A structure-plus-schema snapshot of one category — deliberately NOT
     * {@link Category} itself, so {@code listingCount} (and any future
     * transactional-only field) cannot leak into the cache by accident just
     * because a field was added to the aggregate. See class Javadoc.
     */
    public record CachedCategory(
            String id,
            String name,
            String parentId,
            List<String> subcategoryIds,
            List<AttributeDefinition> attributeDefinitions) {

        static CachedCategory from(Category category) {
            return new CachedCategory(
                    category.getId(), category.getName(), category.getParentId(),
                    category.getSubcategoryIds(), category.getAttributeDefinitions());
        }
    }
}
