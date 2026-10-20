package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.support.MessagingInfraTestBase;

/**
 * Asserts what the relay publishes, so listener containers stay stopped: the email sender would otherwise
 * consume the job before the test reads the queue.
 */
@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class OutboxEmailJobIT extends MessagingInfraTestBase {

    private static final long RECEIVE_TIMEOUT_MS = 10_000;

    @Autowired EmailJobs emailJobs;
    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ObjectMapper objectMapper;

    TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        mongo.remove(new Query(), OutboxEvent.class);
        rabbitAdmin.purgeQueue(RabbitConfig.EMAIL_SEND_QUEUE, false);
    }

    @Test
    void jobRecordedInTransactionIsPublishedToTheEmailQueueAfterCommit() throws Exception {
        EmailJob job = EmailJob.of(EmailKind.ORDER_PAID, "buyer-sub", "order-1", Map.of("title", "Keyboard"));

        transaction.executeWithoutResult(status -> emailJobs.enqueue(job));

        Message received = rabbit.receive(RabbitConfig.EMAIL_SEND_QUEUE, RECEIVE_TIMEOUT_MS);
        assertThat(received).isNotNull();
        JsonNode json = objectMapper.readTree(new String(received.getBody(), StandardCharsets.UTF_8));
        assertThat(json.get("eventId").asText()).isEqualTo(job.eventId());
        assertThat(json.get("kind").asText()).isEqualTo("ORDER_PAID");
        assertThat(json.get("recipientSub").asText()).isEqualTo("buyer-sub");
        assertThat(json.get("dedupKey").asText()).isEqualTo("order-1");
        assertThat(json.get("data").get("title").asText()).isEqualTo("Keyboard");
    }

    @Test
    void jobRecordedInARolledBackTransactionIsNeverPublished() throws Exception {
        EmailJob job = EmailJob.of(EmailKind.ORDER_PAID, "buyer-sub", "order-rollback", Map.of());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            emailJobs.enqueue(job);
            throw new IllegalStateException("producer failed after recording the job");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
        assertThat(rabbit.receive(RabbitConfig.EMAIL_SEND_QUEUE, 3_000)).isNull();
    }

    @Test
    void bulkEnqueueIgnoresDuplicatesAndKeepsOneRowPerJob() {
        EmailJob first = EmailJob.of(EmailKind.NEW_VERSION, "follower-1", "listing-1:v2", Map.of("title", "Editor"));
        EmailJob second = EmailJob.of(EmailKind.NEW_VERSION, "follower-2", "listing-1:v2", Map.of("title", "Editor"));

        assertThat(emailJobs.enqueueAllIgnoringDuplicates(List.of(first, second))).isEqualTo(2);
        assertThat(emailJobs.enqueueAllIgnoringDuplicates(List.of(first, second))).isZero();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isEqualTo(2);

        EmailJob third = EmailJob.of(EmailKind.NEW_VERSION, "follower-3", "listing-1:v2", Map.of("title", "Editor"));
        assertThat(emailJobs.enqueueAllIgnoringDuplicates(List.of(first, third))).isEqualTo(1);
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isEqualTo(3);
    }

    @Test
    void bulkEnqueueOfNothingStoresNothing() {
        assertThat(emailJobs.enqueueAllIgnoringDuplicates(List.of())).isZero();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @Test
    void identicalJobEnqueuedInASecondTransactionFailsWithDuplicateKey() {
        Map<String, String> data = Map.of("title", "Keyboard");
        transaction.executeWithoutResult(status ->
                emailJobs.enqueue(EmailJob.of(EmailKind.ORDER_PAID, "buyer-sub", "order-dup", data)));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                emailJobs.enqueue(EmailJob.of(EmailKind.ORDER_PAID, "buyer-sub", "order-dup", data))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isEqualTo(1);
    }

    @Test
    void jobIdDependsOnKindDedupKeyAndRecipientOnly() {
        EmailJob job = EmailJob.of(EmailKind.NEW_MESSAGE, "a", "conv-1", Map.of("x", "1"));

        assertThat(EmailJob.of(EmailKind.NEW_MESSAGE, "a", "conv-1", Map.of("x", "2")).eventId()).isEqualTo(job.eventId());
        assertThat(EmailJob.of(EmailKind.NEW_MESSAGE, "b", "conv-1", Map.of()).eventId()).isNotEqualTo(job.eventId());
        assertThat(EmailJob.of(EmailKind.NEW_MESSAGE, "a", "conv-2", Map.of()).eventId()).isNotEqualTo(job.eventId());
        assertThat(EmailJob.of(EmailKind.NEW_VERSION, "a", "conv-1", Map.of()).eventId()).isNotEqualTo(job.eventId());
    }

    @Test
    void onlyOrderPaidIsTransactional() {
        assertThat(EmailKind.ORDER_PAID.isTransactional()).isTrue();
        assertThat(EmailKind.NEW_VERSION.isTransactional()).isFalse();
        assertThat(EmailKind.NEW_MESSAGE.isTransactional()).isFalse();
    }
}
