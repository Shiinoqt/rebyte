package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.EmailDispatch;
import it.rebyte.messaging.domain.EmailDispatch.Status;
import it.rebyte.messaging.domain.EmailPreference;
import it.rebyte.messaging.domain.EmailSuppression;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.email.SuppressionService;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.outbox.OutboxRelay;
import it.rebyte.messaging.service.EmailPreferenceService;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestPropertySource(properties = {
        "rebyte.email.new-message-window=PT2S", "rebyte.ses.min-send-interval-ms=0",
        "rebyte.outbox.relay.fixed-delay-ms=3600000", "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms",
        "spring.rabbitmq.listener.simple.retry.max-retries=3"
})
class EmailPipelineIT extends MessagingInfraTestBase {
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final StubHttpServer CATALOG = new StubHttpServer();
    private static final String TITLE = "Monitor città 日本語";
    private static final String PRIVATE_TEXT = "Private message body that must never be emailed";
    private static final String ADDRESS = "seller@example.test";
    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @Autowired EmailJobs jobs;
    @Autowired OutboxRelay relay;
    @Autowired EmailPreferenceService preferences;
    @Autowired SuppressionService suppressions;
    @Autowired TestRestTemplate rest;
    @MockitoBean SesV2Client ses;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", CATALOG::baseUrl);
    }

    @AfterAll
    static void closeStubs() {
        USERS.close();
        CATALOG.close();
    }

    @BeforeEach
    void clean() {
        listeners.getListenerContainer("emailSend").stop();
        admin.purgeQueue(RabbitConfig.EMAIL_SEND_QUEUE, false);
        admin.purgeQueue(RabbitConfig.EMAIL_SEND_DLQ, false);
        admin.purgeQueue(RabbitConfig.ORDER_NOTIFY_QUEUE, false);
        for (Class<?> type : List.of(EmailDispatch.class, EmailPreference.class, EmailSuppression.class,
                OutboxEvent.class, Conversation.class)) {
            mongo.remove(new Query(), type);
        }
        USERS.clearRequests();
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-message-id").build());
        listeners.getListenerContainer("emailSend").start();
    }

    @AfterEach
    void stopEmailLane() {
        listeners.getListenerContainer("emailSend").stop();
    }

    @Test
    void newMessageSendsSummaryAndThrottlesOnlySuccessfulUnreadWindow() throws Exception {
        Chat chat = startChat();
        relay.drainUntilEmptyOrCapped();
        EmailDispatch first = awaitDispatch(chat.first(), Status.SENT);
        assertThat(first.sesMessageId()).isEqualTo("ses-message-id");
        assertThat(first.id()).matches("[a-f0-9]{64}");
        assertThat(first.expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29)));
        assertThat(first.ownerToken()).isNull();
        assertThat(conversation(chat).getSellerLastEmailAt()).isNotNull();
        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(request.capture());
        SendEmailRequest sent = request.getValue();
        assertThat(sent.destination().toAddresses()).containsExactly(ADDRESS);
        assertThat(sent.fromEmailAddress()).isEqualTo("no-reply@example.test");
        assertThat(sent.configurationSetName()).isEqualTo("rebyte-transactional");
        var content = sent.content().simple();
        assertThat(content.subject().data()).isEqualTo("New message from bob about " + TITLE);
        assertThat(content.subject().charset()).isEqualTo("UTF-8");
        assertThat(content.body().html().charset()).isEqualTo("UTF-8");
        assertThat(content.body().text().charset()).isEqualTo("UTF-8");
        assertThat(content.body().html().data()).contains("http://localhost:4200/conversations/" + chat.id())
                .doesNotContain(PRIVATE_TEXT);
        assertThat(content.body().text().data()).contains("http://localhost:4200/conversations/" + chat.id())
                .doesNotContain(PRIVATE_TEXT);
        assertUnsubscribe(sent);
        assertThat(mongo.getCollection("email_dispatch").find().first().toJson()).doesNotContain(ADDRESS);
        EmailJob second = sendMessage(chat);
        relay.drainUntilEmptyOrCapped();
        awaitDispatch(second, Status.SKIPPED_THROTTLED);
        verify(ses, times(1)).sendEmail(any(SendEmailRequest.class));
        Instant firstStamp = conversation(chat).getSellerLastEmailAt();
        await().atMost(Duration.ofSeconds(5)).until(() -> Instant.now().isAfter(firstStamp.plusSeconds(2)));
        EmailJob third = sendMessage(chat);
        relay.drainUntilEmptyOrCapped();
        awaitDispatch(third, Status.SENT);
        verify(ses, times(2)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void readBeforeProcessingSkipsWithoutAddressLookupOrStamp() throws Exception {
        Chat chat = startChat();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(chat.id())), new Update().set("sellerUnread", 0),
                Conversation.class);
        relay.drainUntilEmptyOrCapped();
        awaitDispatch(chat.first(), Status.SKIPPED_READ);
        assertThat(conversation(chat).getSellerLastEmailAt()).isNull();
        assertThat(conversation(chat).getSellerEmailDispatchId()).isNull();
        assertThat(emailRequests("alice")).isZero();
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void paidOrderBypassesOptOutAndOmitsUnsubscribeHeaders() throws Exception {
        directory("buyer");
        preferences.setEnabled("buyer", false);
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> event = Map.of("eventId", eventId, "orderId", "order-1", "listingId", "listing-1",
                "buyerSub", "buyer", "sellerSub", "seller", "listingTitle", TITLE, "listingType", "HARDWARE",
                "occurredAt", Instant.now().toString());
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, RebyteExchanges.ORDER_PAID,
                new Message(mapper.writeValueAsBytes(event), new MessageProperties()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(mongo.count(
                Query.query(Criteria.where("eventId").is(EmailJob.of(EmailKind.ORDER_PAID, "buyer",
                        "ORDER:" + eventId, Map.of()).eventId())), OutboxEvent.class)).isEqualTo(1));
        relay.drainUntilEmptyOrCapped();
        EmailJob job = EmailJob.of(EmailKind.ORDER_PAID, "buyer", "ORDER:" + eventId, Map.of());
        awaitDispatch(job, Status.SENT);
        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(request.capture());
        assertThat(request.getValue().content().simple().headers()).isEmpty();
        assertThat(request.getValue().content().simple().subject().data()).isEqualTo("Payment confirmed for " + TITLE);
    }

    @Test
    void enabledNewVersionHasUnsubscribeAndDisabledVersionSkips() throws Exception {
        EmailJob enabled = version("enabled");
        enqueue(enabled);
        awaitDispatch(enabled, Status.SENT);
        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(request.capture());
        assertUnsubscribe(request.getValue());
        EmailJob disabled = version("disabled");
        preferences.setEnabled("disabled", false);
        enqueue(disabled);
        awaitDispatch(disabled, Status.SKIPPED_OPT_OUT);
        verify(ses, times(1)).sendEmail(any(SendEmailRequest.class));
        assertThat(emailRequests("disabled")).isZero();
    }

    @Test
    void optedOutNewMessageReleasesItsReservation() throws Exception {
        Chat chat = startChat();
        preferences.setEnabled("alice", false);
        relay.drainUntilEmptyOrCapped();
        awaitDispatch(chat.first(), Status.SKIPPED_OPT_OUT);
        assertThat(conversation(chat).getSellerEmailDispatchId()).isNull();
        assertThat(conversation(chat).getSellerLastEmailAt()).isNull();
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void localSuppressionSkipsEveryKindIncludingTransactional(EmailKind kind) throws Exception {
        EmailJob job = job(kind, "suppressed");
        suppressions.record(ADDRESS, "COMPLAINT", null, "bounce-id", Instant.now());
        publish(job);
        awaitDispatch(job, Status.SKIPPED_SUPPRESSED);
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unverified", "absent"})
    void unavailableAddressSkipsAfterOneDirectoryCall(String recipient) throws Exception {
        EmailJob job = version(recipient);
        if (recipient.equals("missing")) {
            USERS.respond("/internal/users/" + recipient + "/email", 404, "{}");
        } else {
            USERS.respond("/internal/users/" + recipient + "/email", 200,
                    "{\"sub\":\"" + recipient + "\",\"email\":"
                    + (recipient.equals("absent") ? "null" : "\"" + ADDRESS + "\"")
                    + ",\"verified\":" + recipient.equals("absent") + "}");
        }
        publish(job);
        awaitDispatch(job, Status.SKIPPED_NO_ADDRESS);
        assertThat(emailRequests(recipient)).isEqualTo(1);
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void permanentRejectionIsAcknowledgedWithoutDlqOrStamp() throws Exception {
        Chat chat = startChat();
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build()).build());
        relay.drainUntilEmptyOrCapped();
        awaitDispatch(chat.first(), Status.FAILED_PERMANENT);
        barrier();
        verify(ses, times(1)).sendEmail(any(SendEmailRequest.class));
        assertThat(admin.getQueueInfo(RabbitConfig.EMAIL_SEND_DLQ).getMessageCount()).isZero();
        assertThat(conversation(chat).getSellerEmailDispatchId()).isNull();
        assertThat(conversation(chat).getSellerLastEmailAt()).isNull();
    }

    @Test
    void throttlingRetriesFourAttemptsThenDlqWithPendingUnownedDispatch() throws Exception {
        Chat chat = startChat();
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(429)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build()).build());
        relay.drainUntilEmptyOrCapped();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(RabbitConfig.EMAIL_SEND_DLQ).getMessageCount()).isEqualTo(1));
        verify(ses, times(4)).sendEmail(any(SendEmailRequest.class));
        EmailDispatch dispatch = mongo.findById(EmailDispatch.idFor(chat.first()), EmailDispatch.class);
        assertThat(dispatch.status()).isEqualTo(Status.PENDING);
        assertThat(dispatch.ownerToken()).isNull();
        assertThat(dispatch.expiresAt()).isNull();
        assertThat(conversation(chat).getSellerLastEmailAt()).isNull();
        assertThat(conversation(chat).getSellerEmailDispatchId()).isNull();
        Message dead = rabbit.receive(RabbitConfig.EMAIL_SEND_DLQ, 1000);
        assertThat(mapper.readValue(dead.getBody(), EmailJob.class)).isEqualTo(chat.first());
    }

    @ParameterizedTest
    @EnumSource(EmailKind.class)
    void identicalJsonRedeliverySendsAtMostOncePerKindRecipientAndDedupKey(EmailKind kind) throws Exception {
        EmailJob job = job(kind, "recipient");
        byte[] raw = mapper.writeValueAsBytes(job);
        publishRaw(raw);
        awaitDispatch(job, Status.SENT);
        publishRaw(raw);
        publishRaw(raw);
        barrier();
        verify(ses, times(1)).sendEmail(any(SendEmailRequest.class));
        assertThat(mongo.count(Query.query(Criteria.where("_id").is(EmailDispatch.idFor(job))), EmailDispatch.class))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "{}", "{\"kind\":\"UNKNOWN\"}",
            "{\"kind\":\"NEW_VERSION\",\"recipientSub\":\"recipient\"}",
            "{\"kind\":\"NEW_VERSION\",\"recipientSub\":\" \",\"dedupKey\":\"key\",\"data\":{}}",
            "{\"kind\":0,\"recipientSub\":\"recipient\",\"dedupKey\":\"key\",\"data\":{}}",
            "{\"kind\":\"NEW_VERSION\",\"recipientSub\":42,\"dedupKey\":\"key\",\"data\":{}}",
            "{\"kind\":\"NEW_VERSION\",\"recipientSub\":\"recipient\",\"dedupKey\":false,\"data\":{}}"})
    void malformedOrMissingRequiredFieldsAreDeadLettered(String raw) {
        publishRaw(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(RabbitConfig.EMAIL_SEND_DLQ).getMessageCount()).isEqualTo(1));
        verify(ses, never()).sendEmail(any(SendEmailRequest.class));
        assertThat(mongo.count(new Query(), EmailDispatch.class)).isZero();
    }

    @Nested
    @TestPropertySource(properties = "rebyte.ses.min-send-interval-ms=500")
    class RateLimited {
        @Test
        void firstSendWaitsAndConsecutiveSesCallsAreAtLeastFiveHundredMillisApart() throws Exception {
            List<Long> calls = new CopyOnWriteArrayList<>();
            when(ses.sendEmail(any(SendEmailRequest.class))).thenAnswer(call -> {
                calls.add(System.nanoTime());
                return SendEmailResponse.builder().messageId("rate-id").build();
            });
            EmailJob first = version("rate-first");
            EmailJob second = version("rate-second");
            long started = System.nanoTime();
            publish(first);
            publish(second);
            awaitDispatch(first, Status.SENT);
            awaitDispatch(second, Status.SENT);
            assertThat(calls).hasSize(2);
            assertThat(Duration.ofNanos(calls.getFirst() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(500));
            assertThat(Duration.ofNanos(calls.get(1) - calls.getFirst())).isGreaterThanOrEqualTo(Duration.ofMillis(500));
        }
    }

    private Chat startChat() throws Exception {
        String listing = UUID.randomUUID().toString();
        directory("alice");
        USERS.respond("/internal/users/bob", 200, "{\"sub\":\"bob\",\"username\":\"bob\"}");
        USERS.respond("/internal/users/alice", 200, "{\"sub\":\"alice\",\"username\":\"alice\"}");
        CATALOG.respond("/internal/listings/" + listing, 200, mapper.writeValueAsString(Map.of("id", listing,
                "sellerSub", "alice", "listingTitle", TITLE, "price", 80, "type", "HARDWARE", "available", true)));
        var response = rest.exchange("/conversations", HttpMethod.POST,
                new HttpEntity<>(Map.of("listingId", listing, "text", PRIVATE_TEXT), headers("bob")), ConversationView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = response.getBody().id();
        OutboxEvent row = mongo.findOne(Query.query(Criteria.where("publishedAt").isNull()), OutboxEvent.class);
        return new Chat(id, mapper.readValue(row.getPayload(), EmailJob.class));
    }

    private EmailJob sendMessage(Chat chat) {
        var response = rest.exchange("/conversations/" + chat.id() + "/messages", HttpMethod.POST,
                new HttpEntity<>(Map.of("text", PRIVATE_TEXT), headers("bob")), MessageView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return EmailJob.of(EmailKind.NEW_MESSAGE, "alice", "MESSAGE:" + response.getBody().id(),
                Map.of("conversationId", chat.id(), "listingTitle", TITLE, "senderUsername", "bob"));
    }

    private EmailJob job(EmailKind kind, String sub) {
        directory(sub);
        Map<String, String> data;
        if (kind == EmailKind.NEW_MESSAGE) {
            String id = UUID.randomUUID().toString();
            mongo.insert(new Conversation(id, id, "HARDWARE", "sender", sub, TITLE, java.math.BigDecimal.TEN,
                    Instant.now(), Instant.now(), null, 1, 0, 1, null, null, null, null, 0, 0));
            data = Map.of("conversationId", id, "listingTitle", TITLE, "senderUsername", "sender");
        } else if (kind == EmailKind.ORDER_PAID) {
            data = Map.of("orderId", "order", "listingId", "listing", "listingTitle", TITLE, "listingType", "HARDWARE");
        } else {
            data = Map.of("listingId", "listing", "listingTitle", TITLE, "versionNumber", "2.0");
        }
        return EmailJob.of(kind, sub, UUID.randomUUID().toString(), data);
    }

    private EmailJob version(String sub) {
        return job(EmailKind.NEW_VERSION, sub);
    }

    private static void directory(String sub) {
        USERS.respond("/internal/users/" + sub + "/email", 200,
                "{\"sub\":\"" + sub + "\",\"email\":\"" + ADDRESS + "\",\"verified\":true}");
    }

    private static long emailRequests(String sub) {
        return USERS.requests().stream().filter(request -> request.path().equals("/internal/users/" + sub + "/email")).count();
    }

    private void enqueue(EmailJob job) {
        jobs.enqueue(job);
        relay.drainUntilEmptyOrCapped();
    }

    private void publish(EmailJob job) throws Exception {
        publishRaw(mapper.writeValueAsBytes(job));
    }

    private void publishRaw(byte[] raw) {
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, RabbitConfig.EMAIL_SEND_ROUTING_KEY,
                new Message(raw, new MessageProperties()));
    }

    private EmailDispatch awaitDispatch(EmailJob job, Status status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(mongo.findById(
                EmailDispatch.idFor(job), EmailDispatch.class)).isNotNull().satisfies(d -> assertThat(d.status()).isEqualTo(status)));
        return mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class);
    }

    private void barrier() throws Exception {
        EmailJob barrier = version("barrier-" + UUID.randomUUID());
        preferences.setEnabled(barrier.recipientSub(), false);
        publish(barrier);
        awaitDispatch(barrier, Status.SKIPPED_OPT_OUT);
    }

    private Conversation conversation(Chat chat) {
        return mongo.findById(chat.id(), Conversation.class);
    }

    private static void assertUnsubscribe(SendEmailRequest request) {
        assertThat(request.content().simple().headers()).anySatisfy(header -> {
            assertThat(header.name()).isEqualTo("List-Unsubscribe");
            assertThat(header.value()).startsWith("<http://localhost:8080/notifications/email-preferences/unsubscribe?token=")
                    .endsWith(">");
        }).anySatisfy(header -> {
            assertThat(header.name()).isEqualTo("List-Unsubscribe-Post");
            assertThat(header.value()).isEqualTo("List-Unsubscribe=One-Click");
        });
    }

    private static HttpHeaders headers(String sub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestJwtSupport.mint(sub));
        return headers;
    }

    private record Chat(String id, EmailJob first) { }
}
