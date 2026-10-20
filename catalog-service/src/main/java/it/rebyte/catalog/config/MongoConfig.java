package it.rebyte.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;

/**
 * The two things Spring Boot does not auto-configure for Mongo, both
 * load-bearing for the transactional outbox and for every price comparison
 * this phase makes (02-RESEARCH.md Pitfalls 1 and 2).
 *
 * <p><b>{@link MongoTransactionManager}.</b> Boot does not register one on
 * its own — without this bean, {@code @Transactional} over a Mongo write is
 * a silent no-op and the outbox stops being atomic even with
 * {@code --replSet rs0} running (infra/docker-compose.yml). Built from the
 * SAME {@link MongoDatabaseFactory} Boot's {@code MongoAutoConfiguration}
 * already registers from {@code spring.mongodb.uri} — this class does
 * NOT declare its own factory or extend
 * {@code AbstractMongoClientConfiguration}, which would take over client
 * construction and stop that property from being honoured.
 *
 * <p><b>{@link MongoCustomConversions} with
 * {@link BigDecimalRepresentation#DECIMAL128}.</b> Spring Data MongoDB 5.x
 * (pulled in transitively by Boot 4.1.1 via {@code spring-data-bom:2026.0.1})
 * removed 4.x's implicit {@code Decimal128} default for {@code BigDecimal};
 * the unconfigured default is a BSON string, which makes every direct Mongo
 * range query or sort on {@code Listing.price} silently lexicographic
 * (Meilisearch's own copy is unaffected — it reads the value at projection
 * time). Verified against the resolved Spring Data MongoDB jar this session:
 * {@code MongoCustomConversions.create(Consumer)},
 * {@code MongoConverterConfigurationAdapter#bigDecimal(BigDecimalRepresentation)}
 * and the nested {@code BigDecimalRepresentation} enum are all present with
 * these exact names.
 */
@Configuration
public class MongoConfig {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }

    @Bean
    MongoCustomConversions mongoCustomConversions() {
        return MongoCustomConversions.create(adapter -> adapter.bigDecimal(BigDecimalRepresentation.DECIMAL128));
    }
}
