package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.EmailDispatch;
import it.rebyte.messaging.domain.EmailDispatch.Status;
import it.rebyte.messaging.domain.EmailPreference;
import it.rebyte.messaging.domain.EmailSuppression;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.messaging.OfferEvent;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.outbox.OutboxRelay;
import it.rebyte.messaging.service.DeadLetterReplayService;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.TooManyRequestsException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = {
        "rebyte.ses.min-send-interval-ms=0",
        "rebyte.outbox.relay.fixed-delay-ms=3600000", "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
        "spring.rabbitmq.listener.simple.retry.max-retries=3"
})
class DeadLetterReplayIT extends MessagingInfraTestBase {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayIT.class);
    private static final StubHttpServer USERS = new StubHttpServer();
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;
    @Autowired EmailJobs jobs;
    @Autowired OutboxRelay relay;
    @Autowired TestRestTemplate rest;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @MockitoBean SesV2Client ses;
    private final ListAppender<ILoggingEvent> replayAudit = new ListAppender<>();

    @AfterEach
    void stopAuditCapture() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeadLetterReplayService.class))
                .detachAppender(replayAudit);
        replayAudit.stop();
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
    }

    @AfterAll
    static void closeStub() {
        USERS.close();
    }

    @BeforeEach
    void clean() {
        replayAudit.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DeadLetterReplayService.class))
                .addAppender(replayAudit);
        RabbitConfig.DEAD_LETTER_ORIGINS.forEach((dlq, queue) -> {
            admin.purgeQueue(queue, false);
            admin.purgeQueue(dlq, false);
        });
        for (Class<?> type : List.of(EmailDispatch.class, EmailPreference.class, EmailSuppression.class,
                OutboxEvent.class, Notification.class, Message.class, Conversation.class)) {
            mongo.remove(new Query(), type);
        }
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("replayed-email").build());
    }

    @Test
    void adminReplaysOneRetriedEmailAndEmptyQueueAnswersNoContent() {
        USERS.respond("/internal/users/recipient/email", 200,
                "{\"sub\":\"recipient\",\"email\":\"recipient@example.test\",\"verified\":true}");
        EmailJob job = EmailJob.of(EmailKind.NEW_VERSION, "recipient", "RELEASE:listing:2.0",
                Map.of("listingId", "listing", "listingTitle", "Editor", "versionNumber", "2.0"));
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(TooManyRequestsException.builder().statusCode(429).build());
        jobs.enqueue(job);
        relay.drainUntilEmptyOrCapped();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(depth(RabbitConfig.EMAIL_SEND_QUEUE)).isZero();
            assertThat(depth(RabbitConfig.EMAIL_SEND_DLQ)).isEqualTo(1);
        });
        verify(ses, times(4)).sendEmail(any(SendEmailRequest.class));
        assertThat(mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class).status()).isEqualTo(Status.PENDING);
        rabbit.execute(channel -> {
            var delivery = channel.basicGet(RabbitConfig.EMAIL_SEND_DLQ, false);
            assertThat(delivery).isNotNull();
            try {
                Object deaths = delivery.getProps().getHeaders().get("x-death");
                boolean carriesOrigin = deaths instanceof List<?> entries && !entries.isEmpty()
                        && entries.getFirst() instanceof Map<?, ?> first
                        && RabbitConfig.EMAIL_SEND_QUEUE.equals(String.valueOf(first.get("queue")));
                log.info("RESEARCH A2: first x-death entry carries originating queue={}", carriesOrigin);
            } finally {
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            }
            return null;
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(depth(RabbitConfig.EMAIL_SEND_DLQ)).isEqualTo(1));
        doReturn(SendEmailResponse.builder().messageId("replayed-email").build())
                .when(ses).sendEmail(any(SendEmailRequest.class));

        ResponseEntity<String> response = replay(RabbitConfig.EMAIL_SEND_DLQ, TestJwtSupport.mint("operator", List.of("ADMIN")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(UUID.fromString(response.getHeaders().getFirst("X-Audit-Id"))).isNotNull();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(depth(RabbitConfig.EMAIL_SEND_QUEUE)).isZero();
            assertThat(depth(RabbitConfig.EMAIL_SEND_DLQ)).isZero();
            EmailDispatch dispatch = mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class);
            assertThat(dispatch.status()).isEqualTo(Status.SENT);
            assertThat(dispatch.sesMessageId()).isEqualTo("replayed-email");
        });
        verify(ses, times(5)).sendEmail(any(SendEmailRequest.class));
        assertThat(replay(RabbitConfig.EMAIL_SEND_DLQ, TestJwtSupport.mint("operator", List.of("ADMIN")))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void nonAdminIsDeniedWithoutRemovingTheDeadLetter() {
        rabbit.send("", RabbitConfig.EMAIL_SEND_DLQ,
                new org.springframework.amqp.core.Message(new byte[] {1}, new MessageProperties()));
        ResponseEntity<String> response = replay(RabbitConfig.EMAIL_SEND_DLQ, TestJwtSupport.mint("member"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).contains("access-denied");
        assertThat(depth(RabbitConfig.EMAIL_SEND_DLQ)).isEqualTo(1);
    }

    @Test
    void anonymousRequestIsUnauthorized() {
        assertThat(replay(RabbitConfig.EMAIL_SEND_DLQ, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminCannotReplayUnknownOrPrimaryQueue() {
        String token = TestJwtSupport.mint("operator", List.of("ADMIN"));
        for (String queue : List.of("notify.unknown.dlq", RabbitConfig.EMAIL_SEND_QUEUE)) {
            var response = replay(queue, token);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody()).contains("\"title\":\"dead-letter-queue-not-found\"")
                    .contains("\"type\":\"urn:rebyte:problem:dead-letter-queue-not-found\"");
            assertReplayAudit("adminSub=operator", "queue=" + queue, "outcome=unknown-queue");
        }
    }

    @Test
    void oneReplayMovesOnlyOneLetterAndPreservesItsBodyAndProperties() throws Exception {
        var emailListener = listeners.getListenerContainer("emailSend");
        emailListener.stop();
        try {
            byte[] first = mapper.writeValueAsBytes(EmailJob.of(EmailKind.NEW_VERSION, "recipient", "first",
                    Map.of("listingId", "listing", "listingTitle", "Editor", "versionNumber", "2.0")));
            byte[] second = mapper.writeValueAsBytes(EmailJob.of(EmailKind.NEW_VERSION, "recipient", "second",
                    Map.of("listingId", "listing", "listingTitle", "Editor", "versionNumber", "3.0")));
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setMessageId("original-id");
            properties.setCorrelationId("original-correlation");
            properties.setHeader("preserved", "value");
            rabbit.send("", RabbitConfig.EMAIL_SEND_DLQ, new org.springframework.amqp.core.Message(first, properties));
            rabbit.send("", RabbitConfig.EMAIL_SEND_DLQ, new org.springframework.amqp.core.Message(second, properties));

            var response = replay(RabbitConfig.EMAIL_SEND_DLQ, TestJwtSupport.mint("operator", List.of("ADMIN")));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertReplayAudit("auditId=" + response.getHeaders().getFirst("X-Audit-Id"), "adminSub=operator",
                    "queue=" + RabbitConfig.EMAIL_SEND_DLQ, "messageId=original-id", "outcome=replayed");
            assertThat(depth(RabbitConfig.EMAIL_SEND_QUEUE)).isEqualTo(1);
            assertThat(depth(RabbitConfig.EMAIL_SEND_DLQ)).isEqualTo(1);
            var moved = rabbit.receive(RabbitConfig.EMAIL_SEND_QUEUE, 1000);
            assertThat(moved).isNotNull();
            assertThat(moved.getBody()).isEqualTo(first);
            assertThat(moved.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(moved.getMessageProperties().getMessageId()).isEqualTo("original-id");
            assertThat(moved.getMessageProperties().getCorrelationId()).isEqualTo("original-correlation");
            assertThat(moved.getMessageProperties().getHeaders()).containsEntry("preserved", "value");
            var parked = rabbit.receive(RabbitConfig.EMAIL_SEND_DLQ, 1000);
            assertThat(parked).isNotNull();
            assertThat(parked.getBody()).isEqualTo(second);
        } finally {
            emailListener.start();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nack", "missing-origin"})
    void brokerPublishFailureLeavesTheOriginalLetterParked(String failureMode) throws Exception {
        var emailListener = listeners.getListenerContainer("emailSend");
        emailListener.stop();
        Queue origin = new RabbitConfig().emailSendQueue();
        try {
            admin.deleteQueue(origin.getName());
            if (failureMode.equals("nack")) {
                Map<String, Object> arguments = new java.util.HashMap<>(origin.getArguments());
                arguments.put("x-max-length", 0);
                arguments.put("x-overflow", "reject-publish");
                admin.declareQueue(new Queue(origin.getName(), true, false, false, arguments));
            }
            byte[] body = mapper.writeValueAsBytes(EmailJob.of(EmailKind.NEW_VERSION, "recipient", "nacked",
                    Map.of("listingId", "listing", "listingTitle", "Editor", "versionNumber", "2.0")));
            MessageProperties properties = new MessageProperties();
            properties.setMessageId("failed-replay-id");
            rabbit.send("", RabbitConfig.EMAIL_SEND_DLQ,
                    new org.springframework.amqp.core.Message(body, properties));

            assertThat(replay(RabbitConfig.EMAIL_SEND_DLQ, TestJwtSupport.mint("operator", List.of("ADMIN")))
                    .getStatusCode().is5xxServerError()).isTrue();
            assertReplayAudit("adminSub=operator", "queue=" + RabbitConfig.EMAIL_SEND_DLQ,
                    "messageId=failed-replay-id", "outcome=failed");
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                if (failureMode.equals("nack")) {
                    assertThat(depth(RabbitConfig.EMAIL_SEND_QUEUE)).isZero();
                } else {
                    assertThat(admin.getQueueInfo(RabbitConfig.EMAIL_SEND_QUEUE)).isNull();
                }
                assertThat(depth(RabbitConfig.EMAIL_SEND_DLQ)).isEqualTo(1);
            });
            var parked = rabbit.receive(RabbitConfig.EMAIL_SEND_DLQ, 1000);
            assertThat(parked).isNotNull();
            assertThat(parked.getBody()).isEqualTo(body);
            assertThat(mongo.findAll(EmailDispatch.class)).isEmpty();
        } finally {
            admin.deleteQueue(origin.getName());
            admin.declareQueue(origin);
            admin.declareBinding(new RabbitConfig().emailSendBinding(origin, new RabbitConfig().marketplaceEventsExchange()));
            emailListener.start();
        }
    }

    @Test
    void offerCardReplayAfterAnotherDeadLetterCycleNeverReachesTheSiblingNotificationQueue() throws Exception {
        OfferEvent event = new OfferEvent(UUID.randomUUID().toString(), "offer", "listing", "Keyboard",
                "buyer", "seller", BigDecimal.TEN, new BigDecimal("20"), "PENDING", null, null, Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("x-death", List.of(Map.of("queue", RabbitConfig.OFFER_CARD_QUEUE,
                "exchange", RebyteExchanges.MARKETPLACE_EVENTS, "reason", "rejected", "count", 1L,
                "routing-keys", List.of(RebyteExchanges.OFFER_CREATED))));
        rabbit.send("", RabbitConfig.OFFER_CARD_DLQ,
                new org.springframework.amqp.core.Message(mapper.writeValueAsBytes(event), properties));
        var cardListener = listeners.getListenerContainers().stream()
                .filter(container -> container instanceof SimpleMessageListenerContainer simple
                        && List.of(simple.getQueueNames()).contains(RabbitConfig.OFFER_CARD_QUEUE))
                .findFirst().orElseThrow();
        cardListener.stop();
        try {
            assertThat(replay(RabbitConfig.OFFER_CARD_DLQ, TestJwtSupport.mint("operator", List.of("ADMIN")))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            // A real broker rejection replaces x-death on RabbitMQ 4.x after a client republishes it.
            rabbit.execute(channel -> {
                var delivery = channel.basicGet(RabbitConfig.OFFER_CARD_QUEUE, false);
                assertThat(delivery).isNotNull();
                channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                return null;
            });
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(depth(RabbitConfig.OFFER_CARD_DLQ)).isEqualTo(1));
        } finally {
            cardListener.start();
        }
        ResponseEntity<String> response = replay(RabbitConfig.OFFER_CARD_DLQ,
                TestJwtSupport.mint("operator", List.of("ADMIN")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(UUID.fromString(response.getHeaders().getFirst("X-Audit-Id"))).isNotNull();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(mongo.findAll(Message.class)).singleElement().satisfies(message -> {
                    assertThat(message.getKind()).isEqualTo(MessageKind.OFFER);
                    assertThat(message.getSourceEventId()).isEqualTo(event.eventId());
                }));
        barrier(RabbitConfig.OFFER_CARD_QUEUE);
        barrier(RabbitConfig.OFFER_NOTIFY_QUEUE);
        assertThat(mongo.findAll(Message.class)).hasSize(1);
        assertThat(mongo.findAll(Notification.class)).isEmpty();
        assertThat(depth(RabbitConfig.OFFER_CARD_DLQ)).isZero();
        assertThat(depth(RabbitConfig.OFFER_NOTIFY_DLQ)).isZero();
        assertThat(replay(RabbitConfig.OFFER_CARD_DLQ, TestJwtSupport.mint("operator", List.of("ADMIN")))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void assertReplayAudit(String... fields) {
        assertThat(replayAudit.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains(fields));
        assertThat(replayAudit.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    }

    private void barrier(String queue) {
        byte[] marker = ("barrier-" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        rabbit.send("", queue, new org.springframework.amqp.core.Message(marker, new MessageProperties()));
        var rejected = rabbit.receive(queue + ".dlq", 15_000);
        assertThat(rejected).isNotNull();
        assertThat(rejected.getBody()).isEqualTo(marker);
        assertThat(depth(queue)).isZero();
    }

    private ResponseEntity<String> replay(String queue, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange("/admin/messaging/dead-letters/" + queue + "/replay", HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private long depth(String queue) {
        var info = admin.getQueueInfo(queue);
        assertThat(info).isNotNull();
        return info.getMessageCount();
    }
}
