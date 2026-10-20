package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import it.rebyte.messaging.domain.Notification;

class NotificationIndexesTest {

    @Test
    void mappingCreatesAUniqueIndexOnlyForUnreadMessageNotifications() {
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(MongoCustomConversions.create(adapter -> {}).getSimpleTypeHolder());
        var resolver = new MongoPersistentEntityIndexResolver(context);
        var indexes = resolver.resolveIndexFor(Notification.class);

        assertThat(indexes).filteredOn(index -> index.getIndexKeys().keySet()
                .stream().toList().equals(List.of("recipientSub", "message.conversationId")))
                .singleElement().satisfies(index -> {
                    assertThat(index.getIndexKeys()).containsExactly(
                            Map.entry("recipientSub", 1), Map.entry("message.conversationId", 1));
                    assertThat(index.getIndexOptions()).containsEntry("unique", true)
                            .containsEntry("partialFilterExpression", new Document("type", "MESSAGE").append("read", false));
                });
    }
}
