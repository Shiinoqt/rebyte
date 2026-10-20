package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.error.ConversationNotFoundException;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.service.ConversationWriter;
import it.rebyte.messaging.service.MessageDraft;
import it.rebyte.messaging.service.MessageViews;
import it.rebyte.messaging.service.MongoTx;
import it.rebyte.messaging.service.ReadService;
import it.rebyte.messaging.service.UnreadSummaryService;
import it.rebyte.messaging.support.MessagingTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
class ConversationAppendIT extends MessagingTestBase {

    private static final String ID = "conversation-1";
    private static final Map<String, String> SNAPSHOT = Map.of("listingTitle", "Keyboard", "senderUsername", "alice");
    @Autowired MongoTemplate mongo;
    @Autowired MongoTx tx;
    @Autowired ConversationWriter writer;
    @Autowired EmailJobs jobs;
    @Autowired MessageViews views;
    @Autowired ReadService reads;
    @Autowired UnreadSummaryService unreadSummary;
    private Conversation initial;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), Message.class);
        mongo.remove(new Query(), OutboxEvent.class);
        mongo.remove(new Query(), Conversation.class);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        initial = mongo.insert(new Conversation(ID, "listing-1", "HARDWARE", "alice", "bob", "Keyboard",
                new BigDecimal("49.90"), now, now, null, 0, 0, 0, null, null, null, null, 0, 0));
    }

    @Test
    void messageCountersNotificationAndEmailCommitTogether() {
        ConversationWriter.Appended appended = tx.execute(() -> {
            ConversationWriter.Appended result = writer.append(ID, chat(" \nPrivate conversation text\t "), SNAPSHOT);
            jobs.enqueue(email(result.message().getId()));
            return result;
        });

        Message message = mongo.findById(appended.message().getId(), Message.class);
        assertThat(message).isNotNull();
        assertThat(message.getSeq()).isEqualTo(1);
        assertThat(message.getText()).isEqualTo("Private conversation text");
        assertThat(message.getSenderSub()).isEqualTo("alice");
        assertThat(message.getRecipientSub()).isEqualTo("bob");
        assertThat(message.isRead()).isFalse();
        assertThat(message.getReadAt()).isNull();
        assertThat(message.getImageKeys()).isNull();
        Conversation stored = conversation();
        assertThat(stored.getMessageSeq()).isEqualTo(1);
        assertThat(stored.unreadFor("alice")).isZero();
        assertThat(stored.unreadFor("bob")).isEqualTo(1);
        assertThat(stored.getLastMessage().messageId()).isEqualTo(message.getId());
        assertThat(stored.getLastMessage().preview()).isEqualTo(message.getText());
        assertThat(stored.getLastMessageAt()).isEqualTo(message.getCreatedAt());
        assertThat(appended.conversation().getMessageSeq()).isEqualTo(stored.getMessageSeq());
        Notification notification = mongo.findAll(Notification.class).getFirst();
        assertThat(notification.getId()).isEqualTo(appended.notification().getId());
        assertThat(notification.getType()).isEqualTo(NotificationType.MESSAGE);
        assertThat(notification.getRecipientSub()).isEqualTo("bob");
        assertThat(notification.getMessage()).isEqualTo(new Notification.MessageTarget(message.getId(), ID, 1));
        assertThat(notification.getSnapshot()).isEqualTo(SNAPSHOT);
        assertThat(notification.isRead()).isFalse();
        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(1);
        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
        List<OutboxEvent> outbox = mongo.findAll(OutboxEvent.class);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.getFirst().getRoutingKey()).isEqualTo(RabbitConfig.EMAIL_SEND_ROUTING_KEY);
        assertThat(outbox.getFirst().getPayload()).contains("NEW_MESSAGE", "conversation-1", "Keyboard", "alice")
                .doesNotContain("Private conversation text");
        MessageView view = views.of(message);
        assertThat(view.text()).isEqualTo(message.getText());
        assertThat(view.seq()).isEqualTo(message.getSeq());
        assertThat(view.offer()).isNull();
        assertThat(MessageView.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("read", "readAt", "recipientSub", "imageKeys");
    }

    @Test
    void unreadMessagesCoalesceToTheNewestTargetWithAStableNotificationId() {
        ConversationWriter.Appended first = tx.execute(() -> writer.append(ID, chat("first"), SNAPSHOT));
        for (int seq = 2; seq < 5; seq++) {
            tx.run(() -> writer.append(ID, chat("next"), SNAPSHOT));
        }
        Map<String, String> latestSnapshot = Map.of("listingTitle", "Updated keyboard", "senderUsername", "alice");
        ConversationWriter.Appended latest = tx.execute(() -> writer.append(ID, chat("latest"), latestSnapshot));

        assertThat(mongo.findAll(Notification.class)).singleElement().satisfies(notification -> {
            assertThat(notification.getId()).isEqualTo(first.notification().getId());
            assertThat(notification.getMessage()).isEqualTo(new Notification.MessageTarget(latest.message().getId(), ID, 5));
            assertThat(notification.getCreatedAt()).isEqualTo(latest.message().getCreatedAt());
            assertThat(notification.getSnapshot()).isEqualTo(latestSnapshot);
            assertThat(notification.getDedupKey()).isEqualTo("MESSAGE:" + first.message().getId());
            assertThat(notification.isRead()).isFalse();
            assertThat(notification.getExpiresAt()).isNull();
            assertThat(latest.notification()).usingRecursiveComparison().isEqualTo(notification);
        });
        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(5);
        assertThat(conversation().unreadFor("bob")).isEqualTo(5);
        assertThat(unreadSummary.summary("bob").notifications()).isEqualTo(1);
        assertThat(unreadSummary.summary("bob").messages()).isEqualTo(5);
    }

    @Test
    void readNotificationIsRetainedAndTheNextMessageOpensANewUnreadNotification() {
        ConversationWriter.Appended first = tx.execute(() -> writer.append(ID, chat("first"), SNAPSHOT));
        ConversationWriter.Appended second = tx.execute(() -> writer.append(ID, chat("second"), SNAPSHOT));
        List<Message> page = mongo.find(new Query(), Message.class);

        assertThat(reads.markRead("bob", conversation(), ReadService.newestSeq(page))).isEqualTo(2);
        Notification read = mongo.findById(first.notification().getId(), Notification.class);
        assertThat(read.isRead()).isTrue();
        assertThat(read.getMessage().messageId()).isEqualTo(second.message().getId());
        assertThat(read.getExpiresAt()).isEqualTo(read.getReadAt().plus(Duration.ofDays(30)));

        ConversationWriter.Appended later = tx.execute(() -> writer.append(ID, chat("later"), SNAPSHOT));

        assertThat(later.notification().getId()).isNotEqualTo(read.getId());
        assertThat(later.notification().getDedupKey()).isEqualTo("MESSAGE:" + later.message().getId());
        assertThat(later.notification().getCreatedAt()).isEqualTo(later.message().getCreatedAt());
        assertThat(later.notification().isRead()).isFalse();
        assertThat(later.notification().getExpiresAt()).isNull();
        assertThat(mongo.findAll(Notification.class)).hasSize(2);
        assertThat(mongo.findById(read.getId(), Notification.class)).usingRecursiveComparison().isEqualTo(read);
        assertThat(unreadSummary.summary("bob").notifications()).isEqualTo(1);
        assertThat(unreadSummary.summary("bob").messages()).isEqualTo(1);
    }

    @Test
    void readingAnOlderPageDoesNotClearTheRetargetedNotification() {
        ConversationWriter.Appended first = tx.execute(() -> writer.append(ID, chat("first"), SNAPSHOT));
        List<Message> page = mongo.find(new Query(), Message.class);
        ConversationWriter.Appended later = tx.execute(() -> writer.append(ID, chat("later"), SNAPSHOT));

        assertThat(reads.markRead("bob", conversation(), ReadService.newestSeq(page))).isEqualTo(1);

        assertThat(mongo.findAll(Notification.class)).singleElement().satisfies(notification -> {
            assertThat(notification.getId()).isEqualTo(first.notification().getId());
            assertThat(notification.getMessage().messageId()).isEqualTo(later.message().getId());
            assertThat(notification.getMessage().seq()).isEqualTo(2);
            assertThat(notification.isRead()).isFalse();
            assertThat(notification.getReadAt()).isNull();
            assertThat(notification.getExpiresAt()).isNull();
        });
        assertThat(mongo.findById(first.message().getId(), Message.class).isRead()).isTrue();
        assertThat(mongo.findById(later.message().getId(), Message.class).isRead()).isFalse();
        assertThat(unreadSummary.summary("bob").notifications()).isEqualTo(1);
        assertThat(unreadSummary.summary("bob").messages()).isEqualTo(1);
    }

    @Test
    void separateConversationsKeepSeparateUnreadNotificationsForTheSameRecipient() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        mongo.insert(new Conversation("conversation-2", "listing-2", "HARDWARE", "alice", "bob", "Mouse",
                new BigDecimal("20.00"), now, null, null, 0, 0, 0, null, null, null, null, 0, 0));
        tx.run(() -> writer.append(ID, chat("first"), SNAPSHOT));
        tx.run(() -> writer.append(ID, chat("second"), SNAPSHOT));
        tx.run(() -> writer.append("conversation-2", chat("other first"), SNAPSHOT));
        tx.run(() -> writer.append("conversation-2", chat("other second"), SNAPSHOT));

        assertThat(mongo.findAll(Notification.class)).hasSize(2).allSatisfy(notification -> {
            assertThat(notification.getRecipientSub()).isEqualTo("bob");
            assertThat(notification.getMessage().seq()).isEqualTo(2);
            assertThat(notification.isRead()).isFalse();
        }).extracting(notification -> notification.getMessage().conversationId())
                .containsExactlyInAnyOrder(ID, "conversation-2");
        assertThat(unreadSummary.summary("bob").notifications()).isEqualTo(2);
        assertThat(unreadSummary.summary("bob").messages()).isEqualTo(4);
    }

    @Test
    void actualNotificationWriteFailureRollsBackEveryLayer() {
        // A database validator rejects the real NotificationWriter upsert after earlier transaction writes.
        mongo.executeCommand(new Document("collMod", "notification")
                .append("validator", new Document("snapshot.forceFailure", new Document("$exists", false))));
        try {
            assertThatThrownBy(() -> tx.run(() -> {
                jobs.enqueue(email("will-rollback"));
                writer.append(ID, chat("will not commit"), Map.of("forceFailure", "true"));
            })).isInstanceOf(DataAccessException.class);
        } finally {
            mongo.executeCommand(new Document("collMod", "notification").append("validator", new Document()));
        }
        assertUnchanged();
    }

    @Test
    void appendWithoutTransactionIsRefusedBeforeWriting() {
        assertThatThrownBy(() -> writer.append(ID, chat("hello"), SNAPSHOT)).isInstanceOf(IllegalStateException.class);
        assertUnchanged();
    }

    @Test
    void absentConversationAndInvalidParticipantsAreHidden() {
        assertThatThrownBy(() -> tx.run(() -> writer.append("missing", chat("hello"), SNAPSHOT)))
                .isInstanceOf(ConversationNotFoundException.class);
        assertThatThrownBy(() -> tx.run(() -> writer.append(ID,
                new MessageDraft("outsider", "bob", MessageKind.CHAT, "hello", null, null, null), SNAPSHOT)))
                .isInstanceOf(ConversationNotFoundException.class);
        assertThatThrownBy(() -> tx.run(() -> writer.append(ID,
                new MessageDraft("alice", "outsider", MessageKind.CHAT, "hello", null, null, null), SNAPSHOT)))
                .isInstanceOf(ConversationNotFoundException.class);
        assertUnchanged();
    }

    @Test
    void repeatedOfferSourceEventRollsBackWithoutConsumingSequenceOrUnread() {
        Message.OfferCard card = new Message.OfferCard("offer-1", Message.OfferEvent.CREATED, "PENDING",
                new BigDecimal("40.00"), new BigDecimal("49.90"), null, null);
        MessageDraft draft = new MessageDraft("alice", "bob", MessageKind.OFFER, null, null, card, "offer-event-1");
        ConversationWriter.Appended first = tx.execute(() -> writer.append(ID, draft, null));
        Conversation beforeDuplicate = conversation();
        assertThat(first.notification()).isNull();
        assertThat(first.message().getSeq()).isEqualTo(1);
        assertThat(beforeDuplicate.getSellerUnread()).isEqualTo(1);
        assertThat(beforeDuplicate.getLastMessage().preview()).isNull();
        MessageView.Offer view = views.of(first.message()).offer();
        assertThat(view).isEqualTo(new MessageView.Offer(card.offerId(), card.event(), card.status(), card.amount(),
                card.askingPrice(), card.expiresAt(), card.rejectionReason()));

        assertThatThrownBy(() -> tx.run(() -> {
            jobs.enqueue(email("duplicate-attempt"));
            writer.append(ID, draft, SNAPSHOT);
        })).isInstanceOf(DuplicateKeyException.class);

        Conversation afterDuplicate = conversation();
        assertThat(afterDuplicate.getMessageSeq()).isEqualTo(1);
        assertThat(afterDuplicate.getBuyerUnread()).isZero();
        assertThat(afterDuplicate.getSellerUnread()).isEqualTo(1);
        assertThat(afterDuplicate.getLastMessage()).isEqualTo(beforeDuplicate.getLastMessage());
        assertThat(afterDuplicate.getLastMessageAt()).isEqualTo(beforeDuplicate.getLastMessageAt());
        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(1);
        assertThat(mongo.findAll(Message.class).getFirst().getId()).isEqualTo(first.message().getId());
        assertThat(mongo.count(new Query(), Notification.class)).isZero();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();

        ConversationWriter.Appended next = tx.execute(() -> writer.append(ID, chat("next"), SNAPSHOT));
        assertThat(next.message().getSeq()).isEqualTo(2);
        assertThat(conversation().getSellerUnread()).isEqualTo(2);
        assertThat(mongo.count(new Query(), Message.class)).isEqualTo(2);
        assertThat(mongo.count(new Query(), Notification.class)).isEqualTo(1);
    }

    private static MessageDraft chat(String text) {
        return new MessageDraft("alice", "bob", MessageKind.CHAT, text, List.of(), null, null);
    }

    private static EmailJob email(String messageId) {
        return EmailJob.of(EmailKind.NEW_MESSAGE, "bob", "MESSAGE:" + messageId,
                Map.of("conversationId", ID, "listingTitle", "Keyboard", "senderUsername", "alice"));
    }

    private Conversation conversation() {
        return mongo.findById(ID, Conversation.class);
    }

    private void assertUnchanged() {
        assertThat(mongo.count(new Query(), Message.class)).isZero();
        assertThat(mongo.count(new Query(), Notification.class)).isZero();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
        Conversation stored = conversation();
        assertThat(stored.getMessageSeq()).isZero();
        assertThat(stored.getBuyerUnread()).isZero();
        assertThat(stored.getSellerUnread()).isZero();
        assertThat(stored.getLastMessage()).isNull();
        assertThat(stored.getLastMessageAt()).isEqualTo(initial.getLastMessageAt());
    }
}
