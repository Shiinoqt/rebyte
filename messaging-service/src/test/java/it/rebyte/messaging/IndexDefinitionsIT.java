package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.support.MessagingTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
class IndexDefinitionsIT extends MessagingTestBase {

    @Autowired MongoTemplate mongo;

    private List<IndexInfo> notificationIndexes() {
        return mongo.indexOps(Notification.class).getIndexInfo();
    }

    private IndexInfo indexOn(Class<?> type, String... keys) {
        return mongo.indexOps(type).getIndexInfo().stream()
                .filter(index -> keys(index).equals(List.of(keys)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing " + type.getSimpleName() + " index on " + List.of(keys)));
    }

    private static List<String> keys(IndexInfo index) {
        return index.getIndexFields().stream().map(IndexField::getKey).toList();
    }

    private IndexInfo byKeys(String... keys) {
        return notificationIndexes().stream()
                .filter(index -> keys(index).equals(List.of(keys)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing notification index on " + List.of(keys)));
    }

    @Test
    void recipientAndDedupKeyIsUnique() {
        assertThat(byKeys("recipientSub", "dedupKey").isUnique()).isTrue();
    }

    @Test
    void unreadMessageNotificationIsUniquePerRecipientAndConversation() {
        IndexInfo index = byKeys("recipientSub", "message.conversationId");

        assertThat(index.isUnique()).isTrue();
        assertThat(index.getIndexFields()).extracting(IndexField::getDirection)
                .containsExactly(org.springframework.data.domain.Sort.Direction.ASC,
                        org.springframework.data.domain.Sort.Direction.ASC);
        assertThat(Document.parse(index.getPartialFilterExpression()))
                .containsExactlyInAnyOrderEntriesOf(new Document("type", "MESSAGE").append("read", false));
    }

    @Test
    void expiresAtHasATimeToLiveOfZeroSeconds() {
        IndexInfo ttl = byKeys("expiresAt");

        assertThat(ttl.getExpireAfter()).contains(Duration.ZERO);
    }

    @Test
    void nonUniqueCompoundIndexesExistWithTheRequestedOrder() {
        IndexInfo created = byKeys("recipientSub", "createdAt", "_id");
        assertThat(created.getIndexFields()).extracting(IndexField::getDirection)
                .containsExactly(org.springframework.data.domain.Sort.Direction.ASC,
                        org.springframework.data.domain.Sort.Direction.DESC,
                        org.springframework.data.domain.Sort.Direction.DESC);

        List<IndexInfo> others = List.of(
                created,
                byKeys("recipientSub", "read", "createdAt"),
                byKeys("recipientSub", "type", "favourite.favouriterSub", "favourite.listingId"),
                byKeys("recipientSub", "type", "follow.followerSub"),
                byKeys("recipientSub", "type", "message.conversationId", "read"));

        assertThat(others).allSatisfy(index -> assertThat(index.isUnique()).isFalse());
    }

    @Test
    void conversationListingAndBuyerIsUnique() {
        assertThat(indexOn(Conversation.class, "listingId", "buyerSub").isUnique()).isTrue();
    }

    @Test
    void conversationListIndexesAreOrderedNewestFirstPerParticipant() {
        for (String participant : List.of("buyerSub", "sellerSub")) {
            IndexInfo index = indexOn(Conversation.class, participant, "lastMessageAt", "_id");

            assertThat(index.isUnique()).isFalse();
            assertThat(index.getIndexFields()).extracting(IndexField::getDirection)
                    .containsExactly(org.springframework.data.domain.Sort.Direction.ASC,
                            org.springframework.data.domain.Sort.Direction.DESC,
                            org.springframework.data.domain.Sort.Direction.DESC);
        }
    }

    @Test
    void messageSequenceIsUniquePerConversation() {
        assertThat(indexOn(Message.class, "conversationId", "seq").isUnique()).isTrue();
    }

    @Test
    void messageSourceEventIdIsUniqueOnlyWhereItExists() {
        IndexInfo index = indexOn(Message.class, "sourceEventId");

        assertThat(index.isUnique()).isTrue();
        assertThat(index.getPartialFilterExpression()).contains("sourceEventId").contains("$exists");
    }

    @Test
    void messageImageKeysAreUniqueOnlyWhereTheyExist() {
        IndexInfo index = indexOn(Message.class, "imageKeys");

        assertThat(index.isUnique()).isTrue();
        assertThat(index.getPartialFilterExpression()).contains("imageKeys").contains("$exists");
    }

    @Test
    void messageUnreadLookupIndexIsNotUnique() {
        assertThat(indexOn(Message.class, "conversationId", "recipientSub", "read", "seq").isUnique()).isFalse();
    }
}
