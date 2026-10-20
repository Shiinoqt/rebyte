package it.rebyte.messaging.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions.BigDecimalRepresentation;

/**
 * Spring Boot does not register a {@link MongoTransactionManager}; without
 * one, {@code @Transactional} over a Mongo write is a silent no-op. It is
 * built from the factory Boot derives from {@code spring.mongodb.uri}, so
 * this class must not declare its own factory.
 *
 * <p>Spring Data MongoDB 5.x stores {@code BigDecimal} as a BSON string
 * unless a representation is configured, which would make numeric queries
 * and sorts on decimal fields lexicographic.
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
