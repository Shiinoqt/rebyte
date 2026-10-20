package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mongodb.client.model.Filters;

import it.rebyte.catalog.support.MongoTestBase;

/**
 * Proves {@link it.rebyte.catalog.config.MongoConfig}'s
 * {@code BigDecimalRepresentation.DECIMAL128} converter actually works, by
 * reading the RAW BSON type back through the driver rather than through the
 * same mapper that wrote it (02-RESEARCH.md Pitfall 1) — a string round-trips
 * through {@link MongoTemplate} just as cleanly as a real
 * {@link Decimal128}, so only inspecting the wire type proves anything.
 *
 * <p>Uses a throwaway document shape rather than the real {@code Listing}
 * aggregate (introduced by Task 2) — the converter configuration this test
 * proves is a Mongo-client-wide concern, not specific to one collection.
 */
@SpringBootTest
class MongoDecimal128IT extends MongoTestBase {

    private static final String COLLECTION = "decimal128_probe";

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void bigDecimalPersistsAsDecimal128NotString() {
        String id = UUID.randomUUID().toString();
        mongoTemplate.insert(new PriceProbe(id, new BigDecimal("199.99")));

        Document raw = mongoTemplate.getCollection(COLLECTION)
                .find(Filters.eq("_id", id))
                .first();

        assertThat(raw).isNotNull();
        assertThat(raw.get("price"))
                .as("price must be a raw BSON Decimal128, not a String — reading it back through "
                        + "the same mapper that wrote it would prove nothing, since a string "
                        + "round-trips just as cleanly")
                .isInstanceOf(Decimal128.class);
    }

    @org.springframework.data.mongodb.core.mapping.Document(collection = COLLECTION)
    record PriceProbe(@Id String id, BigDecimal price) {
    }
}
