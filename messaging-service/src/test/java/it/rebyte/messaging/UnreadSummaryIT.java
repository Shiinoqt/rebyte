package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.dto.UnreadSummary;
import it.rebyte.messaging.service.NotificationFactory;
import it.rebyte.messaging.service.UnreadSummaryService;
import it.rebyte.messaging.support.MessagingTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
class UnreadSummaryIT extends MessagingTestBase {

    @Autowired UnreadSummaryService summaries;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), Conversation.class);
        mongo.remove(new Query(), Message.class);
    }

    private Conversation conversation(String listingId, String buyerSub, String sellerSub, long buyerUnread,
            long sellerUnread) {
        Instant now = Instant.now();
        Conversation conversation = new Conversation(UUID.randomUUID().toString(), listingId, "HARDWARE", buyerSub,
                sellerSub, "Keyboard", new BigDecimal("49.90"), now, now, null, 0, buyerUnread, sellerUnread, null,
                null, null, null, 0, 0);
        return mongo.insert(conversation);
    }

    private void notification(String recipientSub, String reviewId, boolean read) {
        Notification notification = NotificationFactory.review(recipientSub, reviewId, java.util.Map.of());
        if (read) {
            notification.markRead(Instant.now(), java.time.Duration.ofDays(30));
        }
        mongo.insert(notification);
    }

    @Test
    void userIsBuyerInOneConversationAndSellerInAnotherAndCountsUnreadNotifications() {
        conversation("listing-1", "user", "other-seller", 2, 7);
        conversation("listing-2", "other-buyer", "user", 9, 3);
        notification("user", "r1", false);
        notification("user", "r2", false);
        notification("user", "r3", true);

        assertThat(summaries.summary("user")).isEqualTo(new UnreadSummary(2, 5));
    }

    @Test
    void secondConversationForTheSameListingAndBuyerIsRejected() {
        conversation("listing-1", "buyer", "seller", 0, 0);

        assertThatThrownBy(() -> conversation("listing-1", "buyer", "seller", 0, 0))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mongo.count(new Query(), Conversation.class)).isEqualTo(1);
    }

    private Message message(String conversationId, long seq, String sourceEventId, java.util.List<String> imageKeys) {
        return new Message(UUID.randomUUID().toString(), conversationId, seq, "buyer", "seller",
                sourceEventId == null ? MessageKind.CHAT : MessageKind.OFFER, "hello", imageKeys, null, sourceEventId,
                Instant.now(), false, null);
    }

    @Test
    void userWithNothingGetsZeroTotals() {
        assertThat(summaries.summary("nobody")).isEqualTo(new UnreadSummary(0, 0));
    }

    @Test
    void otherUsersConversationsAndNotificationsAreNotCounted() {
        conversation("listing-1", "someone", "someone-else", 4, 6);
        notification("someone", "r1", false);

        assertThat(summaries.summary("user")).isEqualTo(new UnreadSummary(0, 0));
    }

    @Test
    void readNotificationsOfEveryTypeAreIgnoredAndUnreadOnesOfEveryTypeAreCounted() {
        notification("user", "r1", true);
        mongo.insert(NotificationFactory.follow("user", "follower-1", java.util.Map.of()));
        mongo.insert(NotificationFactory.offerReceived("user", "offer-1", java.util.Map.of()));

        assertThat(summaries.summary("user")).isEqualTo(new UnreadSummary(2, 0));
    }

    @Test
    void conversationWithoutUnreadMessagesAddsNothing() {
        conversation("listing-1", "user", "seller", 0, 8);

        assertThat(summaries.summary("user").messages()).isZero();
    }

    @Test
    void sameListingWithDifferentBuyersIsAllowed() {
        conversation("listing-1", "buyer-1", "seller", 0, 0);
        conversation("listing-1", "buyer-2", "seller", 0, 0);

        assertThat(mongo.count(new Query(), Conversation.class)).isEqualTo(2);
    }

    @Test
    void sameSourceEventIdCannotBeStoredTwice() {
        mongo.insert(message("c1", 1, "evt-1", null));

        assertThatThrownBy(() -> mongo.insert(message("c1", 2, "evt-1", null)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(1);
    }

    @Test
    void chatMessagesWithoutSourceEventIdAreNotConstrainedByThatIndex() {
        mongo.insert(message("c1", 1, null, null));
        mongo.insert(message("c1", 2, null, null));

        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(2);
    }

    @Test
    void sameSequenceInTheSameConversationIsRejectedButNotAcrossConversations() {
        mongo.insert(message("c1", 1, null, null));
        mongo.insert(message("c2", 1, null, null));

        assertThatThrownBy(() -> mongo.insert(message("c1", 1, null, null))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void anImageKeyAttachesToOneMessageOnly() {
        mongo.insert(message("c1", 1, null, java.util.List.of("chat/a.png", "chat/b.png")));

        assertThatThrownBy(() -> mongo.insert(message("c1", 2, null, java.util.List.of("chat/c.png", "chat/b.png"))))
                .isInstanceOf(DuplicateKeyException.class);
        mongo.insert(message("c1", 3, null, java.util.List.of("chat/d.png")));
        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(2);
    }

    @Test
    void messagesWithoutImagesAreNotConstrainedByTheImageKeyIndex() {
        mongo.insert(message("c1", 1, null, null));
        mongo.insert(message("c1", 2, null, null));
        mongo.insert(message("c1", 3, null, java.util.List.of("chat/a.png")));

        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(3);
    }
}
