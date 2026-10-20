package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.MongoException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
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
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.email.EmailSendService;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.service.EmailPreferenceService;
import it.rebyte.messaging.service.ReadService;
import it.rebyte.messaging.support.MessagingInfraTestBase;
import it.rebyte.messaging.support.StubHttpServer;
import it.rebyte.messaging.support.TestJwtSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-auth")
@Import(TestJwtSupport.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "rebyte.email.preparation-reaper.fixed-delay-ms=3600000",
        "rebyte.ses.min-send-interval-ms=0", "rebyte.email.new-message-window=PT15M",
        "rebyte.outbox.relay.fixed-delay-ms=3600000", "rebyte.outbox.reaper.fixed-delay-ms=3600000",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"
})
class EmailMultiInstanceIT extends MessagingInfraTestBase {
    private static final StubHttpServer USERS = new StubHttpServer();
    private static final StubHttpServer CATALOG = new StubHttpServer();
    @MockitoBean SesV2Client firstSes;
    @MockitoSpyBean MongoTemplate mongo;
    @MockitoSpyBean MongoTransactionManager transactionManager;
    @MockitoSpyBean EmailSendService firstSender;
    @Autowired RabbitListenerEndpointRegistry firstListeners;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired ObjectMapper mapper;
    @Autowired ReadService reads;
    @Autowired EmailPreferenceService preferences;
    private final SesV2Client secondSes = mock(SesV2Client.class);
    private ConfigurableApplicationContext secondContext;
    private EmailSendService secondSender;
    private RabbitListenerEndpointRegistry secondListeners;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.user-service.url", USERS::baseUrl);
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url", CATALOG::baseUrl);
    }

    @BeforeAll
    void startSecondInstance() {
        secondContext = new SpringApplicationBuilder(MessagingServiceApplication.class, TestJwtSupport.class)
                .profiles("local-auth").initializers(context -> ((GenericApplicationContext) context)
                        .registerBean("secondInstanceSes", SesV2Client.class, () -> secondSes,
                                definition -> definition.setPrimary(true)))
                .run("--server.port=0", "--eureka.client.enabled=false",
                        "--spring.mongodb.uri=" + MONGO.getReplicaSetUrl(mongo.getDb().getName()),
                        "--spring.data.mongodb.auto-index-creation=true",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--spring.rabbitmq.host=" + RABBIT.getHost(),
                        "--spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                        "--spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                        "--spring.rabbitmq.password=" + RABBIT.getAdminPassword(),
                        "--spring.rabbitmq.listener.simple.prefetch=1",
                        "--spring.rabbitmq.listener.simple.default-requeue-rejected=false",
                        "--spring.rabbitmq.listener.simple.retry.enabled=true",
                        "--spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
                        "--spring.rabbitmq.listener.simple.retry.max-interval=200ms",
                        "--spring.rabbitmq.listener.simple.retry.max-retries=3",
                        "--rebyte.media.bucket=messaging-test-bucket", "--rebyte.media.region=eu-south-1",
                        "--rebyte.messaging.aws.access-key-id=AKIAMESSAGINGTEST000",
                        "--rebyte.messaging.aws.secret-access-key=messaging-test-secret-not-real",
                        "--rebyte.ses.from-address=no-reply@example.test", "--rebyte.ses.events.enabled=false",
                        "--rebyte.email.preparation-reaper.fixed-delay-ms=3600000",
                        "--rebyte.ses.min-send-interval-ms=0", "--rebyte.email.new-message-window=PT15M",
                        "--rebyte.email.unsubscribe.secret=messaging-test-unsubscribe-secret-0123456789",
                        "--rebyte.outbox.relay.fixed-delay-ms=3600000", "--rebyte.outbox.reaper.fixed-delay-ms=3600000",
                        "--spring.cloud.openfeign.client.config.user-service.url=" + USERS.baseUrl(),
                        "--spring.cloud.openfeign.client.config.catalog-service.url=" + CATALOG.baseUrl(),
                        "--spring.cloud.openfeign.client.config.order-service.url=http://127.0.0.1:1");
        secondSender = secondContext.getBean(EmailSendService.class);
        secondListeners = secondContext.getBean(RabbitListenerEndpointRegistry.class);
        assertThat(secondContext.getBean(MongoTemplate.class).getDb().getName()).isEqualTo(mongo.getDb().getName());
        assertThat(secondSender).isNotSameAs(firstSender);
    }

    @AfterAll
    void closeSecondInstance() {
        if (secondContext != null) {
            secondContext.close();
        }
        USERS.close();
        CATALOG.close();
    }

    @BeforeEach
    void cleanWithListenersPaused() {
        pauseListeners();
        reset(secondSes);
        admin.purgeQueue(RabbitConfig.EMAIL_SEND_QUEUE, false);
        admin.purgeQueue(RabbitConfig.EMAIL_SEND_DLQ, false);
        for (Class<?> type : List.of(EmailDispatch.class, Conversation.class, EmailPreference.class,
                EmailSuppression.class, OutboxEvent.class)) {
            mongo.remove(new Query(), type);
        }
        when(firstSes.sendEmail(any(SendEmailRequest.class))).thenReturn(accepted("first-id"));
        when(secondSes.sendEmail(any(SendEmailRequest.class))).thenReturn(accepted("second-id"));
        USERS.respond("/internal/users/seller/email", 200,
                "{\"sub\":\"seller\",\"email\":\"seller@example.test\",\"verified\":true}");
    }

    @AfterEach
    void pauseListeners() {
        firstListeners.getListenerContainer("emailSend").stop();
        if (secondListeners != null) {
            secondListeners.getListenerContainer("emailSend").stop();
        }
    }

    @Test
    void singleActiveLaneSerializesDuplicatesAndPairJobsAndStandbyTakesOver() throws Exception {
        EmailJob first = messageJob(newConversation(), "first");
        EmailJob second = messageJob(first.data().get("conversationId"), "second");
        EmailJob third = messageJob(first.data().get("conversationId"), "third");
        BlockingSend block = new BlockingSend();
        AtomicReference<String> active = new AtomicReference<>();
        doAnswer(call -> { active.set("first"); return block.send(); }).when(firstSes).sendEmail(any(SendEmailRequest.class));
        doAnswer(call -> { active.set("second"); return block.send(); }).when(secondSes).sendEmail(any(SendEmailRequest.class));
        firstListeners.getListenerContainer("emailSend").start();
        secondListeners.getListenerContainer("emailSend").start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(RabbitConfig.EMAIL_SEND_QUEUE).getConsumerCount()).isEqualTo(2));
        try {
            publish(first);
            publish(first);
            publish(second);
            publish(third);
            block.awaitEntry();
            await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                assertThat(totalCalls()).isEqualTo(1);
                assertThat(dispatch(first).status()).isEqualTo(Status.SENDING);
                assertThat(conversation(first).getSellerLastEmailAt()).isNull();
                assertThat(conversation(first).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(first));
                assertThat(dispatch(second)).isNull();
                assertThat(dispatch(third)).isNull();
            });
        } finally {
            block.release();
        }
        awaitStatus(first, Status.SENT);
        awaitStatus(second, Status.SKIPPED_THROTTLED);
        awaitStatus(third, Status.SKIPPED_THROTTLED);
        assertThat(totalCalls()).isEqualTo(1);
        assertThat(mongo.count(new Query(), EmailDispatch.class)).isEqualTo(3);
        assertThat(conversation(first).getSellerEmailDispatchId()).isNull();
        String originalActive = active.get();
        if (originalActive.equals("first")) {
            firstListeners.getListenerContainer("emailSend").stop();
        } else {
            secondListeners.getListenerContainer("emailSend").stop();
        }
        EmailJob takeover = versionJob("takeover");
        publish(takeover);
        awaitStatus(takeover, Status.SENT);
        assertThat(totalCalls()).isEqualTo(2);
        assertThat(dispatch(takeover).sesMessageId()).isEqualTo("blocked-id");
        assertThat(active.get()).isNotEqualTo(originalActive);
        verify(originalActive.equals("first") ? secondSes : firstSes).sendEmail(any(SendEmailRequest.class));
        assertThat(admin.getQueueInfo(RabbitConfig.EMAIL_SEND_DLQ).getMessageCount()).isZero();
    }

    @Test
    void concurrentRealServicesCannotOwnTheSameDispatchEvenWhenListenersArePaused() throws Exception {
        EmailJob job = messageJob(newConversation(), "same");
        BlockingSend block = blockFirst();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(() -> firstSender.process(job));
            try {
                block.awaitEntry();
                assertThatThrownBy(() -> secondSender.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
                assertThat(totalCalls()).isEqualTo(1);
                assertThat(dispatch(job).expiresAt()).isNull();
            } finally {
                block.release();
            }
            sending.get(10, TimeUnit.SECONDS);
        }
        secondSender.process(job);
        assertThat(totalCalls()).isEqualTo(1);
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
    }

    @Test
    void differentDispatchCannotConsumeAReservedPairWindowBeforeSuccess() throws Exception {
        String conversation = newConversation();
        EmailJob first = messageJob(conversation, "owner");
        EmailJob second = messageJob(conversation, "contender");
        BlockingSend block = blockFirst();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(() -> firstSender.process(first));
            try {
                block.awaitEntry();
                assertThatThrownBy(() -> secondSender.process(second)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("slot");
                assertThat(dispatch(second).status()).isEqualTo(Status.PENDING);
                assertThat(dispatch(second).ownerToken()).isNull();
                assertThat(conversation(first).getSellerLastEmailAt()).isNull();
                assertThat(conversation(first).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(first));
                assertThat(totalCalls()).isEqualTo(1);
            } finally {
                block.release();
            }
            sending.get(10, TimeUnit.SECONDS);
        }
        secondSender.process(second);
        assertThat(dispatch(second).status()).isEqualTo(Status.SKIPPED_THROTTLED);
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void definite429ReleasesPairAndSameJobSucceedsOnTheOtherInstance() {
        EmailJob job = messageJob(newConversation(), "retry");
        when(firstSes.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(429)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build()).build());
        assertThatThrownBy(() -> firstSender.process(job)).isInstanceOf(IllegalStateException.class);
        assertReleasedPending(job);
        secondSender.process(job);
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
        assertThat(dispatch(job).sesMessageId()).isEqualTo("second-id");
        assertThat(conversation(job).getSellerLastEmailAt()).isNotNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        assertThat(totalCalls()).isEqualTo(2);
    }

    @Test
    void listenerRetriesDefinite429AndDoesNotLoseTheJob() throws Exception {
        EmailJob job = messageJob(newConversation(), "listener-retry");
        when(firstSes.sendEmail(any(SendEmailRequest.class))).thenThrow(SesV2Exception.builder().statusCode(429)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build()).build())
                .thenReturn(accepted("retried-id"));
        firstListeners.getListenerContainer("emailSend").start();
        publish(job);
        awaitStatus(job, Status.SENT);
        assertThat(dispatch(job).sesMessageId()).isEqualTo("retried-id");
        verify(firstSes, times(2)).sendEmail(any(SendEmailRequest.class));
        verify(secondSes, never()).sendEmail(any(SendEmailRequest.class));
        assertThat(conversation(job).getSellerLastEmailAt()).isNotNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        assertThat(admin.getQueueInfo(RabbitConfig.EMAIL_SEND_DLQ).getMessageCount()).isZero();
    }

    @Test
    void directoryFailureBeforeSesReleasesOwnershipAndRetriesOnTheOtherInstance() {
        EmailJob job = messageJob(newConversation(), "directory");
        USERS.respond("/internal/users/seller/email", 503, "{}");
        assertThatThrownBy(() -> firstSender.process(job)).isInstanceOf(RuntimeException.class);
        assertReleasedPending(job);
        assertThat(totalCalls()).isZero();
        USERS.respond("/internal/users/seller/email", 200,
                "{\"sub\":\"seller\",\"email\":\"seller@example.test\",\"verified\":true}");
        secondSender.process(job);
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void permanentFailureReleasesThePairWithoutStampAndAnotherJobCanSend() {
        String conversation = newConversation();
        EmailJob rejected = messageJob(conversation, "rejected");
        when(firstSes.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build()).build());
        firstSender.process(rejected);
        assertThat(dispatch(rejected).status()).isEqualTo(Status.FAILED_PERMANENT);
        assertThat(dispatch(rejected).ownerToken()).isNull();
        assertThat(dispatch(rejected).expiresAt()).isNotNull();
        assertThat(conversation(rejected).getSellerEmailDispatchId()).isNull();
        assertThat(conversation(rejected).getSellerLastEmailAt()).isNull();
        EmailJob next = messageJob(conversation, "after-rejection");
        secondSender.process(next);
        assertThat(dispatch(next).status()).isEqualTo(Status.SENT);
        assertThat(totalCalls()).isEqualTo(2);
    }

    @Test
    void acceptedSesRetriesOnlyCompletionMongoTransaction() {
        EmailJob job = messageJob(newConversation(), "completion");
        AtomicInteger completionAttempts = failCompletion(2);
        firstSender.process(job);
        assertThat(completionAttempts.get()).isEqualTo(3);
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
        assertThat(dispatch(job).sesMessageId()).isEqualTo("first-id");
        assertThat(conversation(job).getSellerLastEmailAt()).isNotNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        secondSender.process(job);
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void acceptedSesRetriesCompletionWhenMongoCommitResponseIsLost() {
        EmailJob job = messageJob(newConversation(), "lost-commit-response");
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicInteger completionCommits = new AtomicInteger();
        when(firstSes.sendEmail(any(SendEmailRequest.class))).thenAnswer(call -> {
            accepted.set(true);
            return accepted("commit-id");
        });
        doAnswer(call -> {
            call.callRealMethod();
            if (accepted.get() && completionCommits.incrementAndGet() == 1) {
                MongoException lostResponse = new MongoException("Injected lost commit response");
                lostResponse.addLabel("UnknownTransactionCommitResult");
                throw new TransactionSystemException("Could not confirm Mongo commit", lostResponse);
            }
            return null;
        }).when(transactionManager).commit(any(TransactionStatus.class));
        firstSender.process(job);
        assertThat(completionCommits.get()).isEqualTo(2);
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
        assertThat(dispatch(job).sesMessageId()).isEqualTo("commit-id");
        assertThat(conversation(job).getSellerLastEmailAt()).isNotNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        secondSender.process(job);
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void exhaustedCompletionPersistsSendingOwnerAndNeverResendsEvenAfterTimePasses() {
        EmailJob job = messageJob(newConversation(), "unresolved-completion");
        AtomicInteger completionAttempts = failCompletion(100);
        assertThatThrownBy(() -> firstSender.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(completionAttempts.get()).isEqualTo(5);
        assertThat(dispatch(job).status()).isEqualTo(Status.SENDING);
        assertThat(dispatch(job).ownerToken()).isNotBlank();
        assertThat(dispatch(job).expiresAt()).isNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(job));
        assertThat(conversation(job).getSellerLastEmailAt()).isNull();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(EmailDispatch.idFor(job))),
                new Update().set("sendStartedAt", Instant.EPOCH), EmailDispatch.class);
        assertThatThrownBy(() -> secondSender.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void transportUncertaintyReleasesPairButReplayCannotSend() throws Exception {
        EmailJob job = messageJob(newConversation(), "unknown");
        when(firstSes.sendEmail(any(SendEmailRequest.class))).thenThrow(SdkClientException.create("response lost"));
        firstListeners.getListenerContainer("emailSend").start();
        publish(job);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                admin.getQueueInfo(RabbitConfig.EMAIL_SEND_DLQ).getMessageCount()).isEqualTo(1));
        pauseListeners();
        assertThat(dispatch(job).status()).isEqualTo(Status.DELIVERY_UNKNOWN);
        verify(firstSender, times(1)).process(job);
        assertThat(dispatch(job).ownerToken()).isNotBlank();
        assertThat(dispatch(job).expiresAt()).isNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        assertThat(conversation(job).getSellerLastEmailAt()).isNull();
        assertThatThrownBy(() -> firstSender.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> secondSender.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(totalCalls()).isEqualTo(1);
        EmailJob next = messageJob(job.data().get("conversationId"), "new-job");
        secondSender.process(next);
        assertThat(dispatch(next).status()).isEqualTo(Status.SENT);
        assertThat(dispatch(job).status()).isEqualTo(Status.DELIVERY_UNKNOWN);
        assertThat(totalCalls()).isEqualTo(2);
    }

    @Test
    void readDuringInFlightSendAdvancesGenerationAndLateCompletionCannotRestoreStamp() throws Exception {
        EmailJob job = messageJob(newConversation(), "before-read");
        BlockingSend block = blockFirst();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(() -> firstSender.process(job));
            try {
                block.awaitEntry();
                reads.markRead("seller", conversation(job), 0);
                mongo.updateFirst(Query.query(Criteria.where("_id").is(job.data().get("conversationId"))),
                        new Update().set("sellerUnread", 0), Conversation.class);
                assertThat(conversation(job).getSellerEmailGeneration()).isEqualTo(1);
                assertThat(conversation(job).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(job));
            } finally {
                block.release();
            }
            sending.get(10, TimeUnit.SECONDS);
        }
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
        assertThat(conversation(job).getSellerLastEmailAt()).isNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(job.data().get("conversationId"))),
                new Update().set("sellerUnread", 1), Conversation.class);
        EmailJob afterRead = messageJob(job.data().get("conversationId"), "after-read");
        secondSender.process(afterRead);
        assertThat(dispatch(afterRead).status()).isEqualTo(Status.SENT);
        assertThat(conversation(afterRead).getSellerEmailGeneration()).isEqualTo(1);
        assertThat(conversation(afterRead).getSellerLastEmailAt()).isNotNull();
        assertThat(totalCalls()).isEqualTo(2);
    }

    @Test
    void realSesClientDisablesSdkRetriesSoUncertainAcceptanceCannotBeRepeatedInsideTheSdk() {
        SesV2Client configuredClient = secondContext.getBean("sesV2Client", SesV2Client.class);
        assertThat(configuredClient.serviceClientConfiguration().overrideConfiguration().retryStrategy()
                .orElseThrow().maxAttempts()).isEqualTo(1);
    }

    @Test
    void pendingAndUnresolvedOwnersHaveNoExpiryAndOnlyTerminalExpiryIsIndexed() {
        EmailJob pending = messageJob(newConversation(), "pending");
        mongo.insert(EmailDispatch.pending(pending, Instant.EPOCH));
        assertThat(dispatch(pending).expiresAt()).isNull();
        assertThat(mongo.indexOps(EmailDispatch.class).getIndexInfo()).anySatisfy(index -> {
            assertThat(index.getName()).isEqualTo("email_dispatch_expiry");
            assertThat(index.getIndexFields()).extracting(field -> field.getKey()).containsExactly("expiresAt");
            assertThat(index.getExpireAfter()).contains(Duration.ZERO);
        }).noneSatisfy(index -> {
            assertThat(index.getIndexFields()).extracting(field -> field.getKey()).contains("createdAt");
            assertThat(index.getExpireAfter()).isPresent();
        });
    }

    @Test
    void stalePreparationReclaimsDispatchAndSlotButLeavesFreshAndSendingOwnersAlone() {
        EmailJob stale = messageJob(newConversation(), "stale");
        EmailJob fresh = messageJob(newConversation(), "fresh");
        EmailJob sending = messageJob(newConversation(), "sending");
        reserve(stale, Status.PREPARING, Instant.EPOCH);
        reserve(fresh, Status.PREPARING, Instant.now());
        reserve(sending, Status.SENDING, Instant.EPOCH);
        secondSender.reclaimStalePreparations();
        assertReleasedPending(stale);
        assertThat(dispatch(stale).preparationStartedAt()).isNull();
        assertThat(dispatch(fresh).status()).isEqualTo(Status.PREPARING);
        assertThat(conversation(fresh).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(fresh));
        assertThat(dispatch(sending).status()).isEqualTo(Status.SENDING);
        assertThat(conversation(sending).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(sending));
        firstSender.process(stale);
        assertThat(dispatch(stale).status()).isEqualTo(Status.SENT);
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void resumedStaleOwnerCannotSendOrReleaseNewOwnersReservation() throws Exception {
        EmailJob job = messageJob(newConversation(), "resumed");
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(call -> {
            Update update = call.getArgument(1);
            org.bson.Document set = update.getUpdateObject().get("$set", org.bson.Document.class);
            if (set != null && set.get("status") == Status.SENDING && paused.compareAndSet(false, true)) {
                preparing.countDown();
                assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(mongo).updateFirst(any(Query.class), any(Update.class), eq(EmailDispatch.class));
        BlockingSend replacement = new BlockingSend();
        doAnswer(call -> replacement.send()).when(secondSes).sendEmail(any(SendEmailRequest.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var staleOwner = executor.submit(() -> firstSender.process(job));
            try {
                assertThat(preparing.await(10, TimeUnit.SECONDS)).isTrue();
                mongo.updateFirst(Query.query(Criteria.where("_id").is(EmailDispatch.idFor(job))),
                        new Update().set("preparationStartedAt", Instant.EPOCH), EmailDispatch.class);
                secondSender.reclaimStalePreparations();
                assertReleasedPending(job);
                var replacementOwner = executor.submit(() -> secondSender.process(job));
                replacement.awaitEntry();
                String owner = dispatch(job).ownerToken();
                resume.countDown();
                assertThatThrownBy(() -> staleOwner.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(dispatch(job).status()).isEqualTo(Status.SENDING);
                assertThat(dispatch(job).ownerToken()).isEqualTo(owner);
                assertThat(conversation(job).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(job));
                replacement.release();
                replacementOwner.get(10, TimeUnit.SECONDS);
            } finally {
                resume.countDown();
                replacement.release();
            }
        }
        assertThat(dispatch(job).status()).isEqualTo(Status.SENT);
        assertThat(conversation(job).getSellerLastEmailAt()).isNotNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        verify(firstSes, never()).sendEmail(any(SendEmailRequest.class));
        assertThat(totalCalls()).isEqualTo(1);
    }

    @Test
    void reclaimRollsBackDispatchWhenSlotReleaseFails() {
        EmailJob stale = messageJob(newConversation(), "rollback");
        reserve(stale, Status.PREPARING, Instant.EPOCH);
        doThrow(new DataAccessResourceFailureException("Injected slot release failure"))
                .when(mongo).updateMulti(any(Query.class), any(Update.class), eq(Conversation.class));
        assertThatThrownBy(() -> firstSender.reclaimStalePreparations())
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(dispatch(stale).status()).isEqualTo(Status.PREPARING);
        assertThat(dispatch(stale).ownerToken()).isEqualTo("dead-owner");
        assertThat(conversation(stale).getSellerEmailDispatchId()).isEqualTo(EmailDispatch.idFor(stale));
        verify(firstSes, never()).sendEmail(any(SendEmailRequest.class));
    }

    private void reserve(EmailJob job, Status status, Instant started) {
        mongo.insert(new EmailDispatch(EmailDispatch.idFor(job), job.kind(), job.recipientSub(), job.dedupKey(),
                status, "dead-owner", started, status == Status.SENDING ? started : null,
                null, Instant.EPOCH, started, null));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(job.data().get("conversationId"))),
                new Update().set("sellerEmailDispatchId", EmailDispatch.idFor(job)), Conversation.class);
    }

    private AtomicInteger failCompletion(int failures) {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            Update update = call.getArgument(1);
            org.bson.Document set = update.getUpdateObject().get("$set", org.bson.Document.class);
            if (set != null && set.get("status") == Status.SENT && attempts.incrementAndGet() <= failures) {
                throw new DataAccessResourceFailureException("Injected completion write failure");
            }
            return call.callRealMethod();
        }).when(mongo).updateFirst(any(Query.class), any(Update.class), eq(EmailDispatch.class));
        return attempts;
    }

    private void assertReleasedPending(EmailJob job) {
        assertThat(dispatch(job).status()).isEqualTo(Status.PENDING);
        assertThat(dispatch(job).ownerToken()).isNull();
        assertThat(dispatch(job).expiresAt()).isNull();
        assertThat(conversation(job).getSellerEmailDispatchId()).isNull();
        assertThat(conversation(job).getSellerLastEmailAt()).isNull();
    }

    private BlockingSend blockFirst() {
        BlockingSend block = new BlockingSend();
        doAnswer(call -> block.send()).when(firstSes).sendEmail(any(SendEmailRequest.class));
        return block;
    }

    private int totalCalls() {
        return (int) java.util.stream.Stream.of(firstSes, secondSes)
                .flatMap(client -> mockingDetails(client).getInvocations().stream())
                .filter(call -> call.getMethod().getName().equals("sendEmail")
                        && call.getArguments()[0] instanceof SendEmailRequest).count();
    }

    private String newConversation() {
        String id = UUID.randomUUID().toString();
        mongo.insert(new Conversation(id, id, "HARDWARE", "buyer", "seller", "Monitor", BigDecimal.TEN,
                Instant.now(), Instant.now(), null, 1, 0, 1, null, null, null, null, 0, 0));
        return id;
    }

    private static EmailJob messageJob(String conversation, String key) {
        return EmailJob.of(EmailKind.NEW_MESSAGE, "seller", "MESSAGE:" + key,
                Map.of("conversationId", conversation, "listingTitle", "Monitor", "senderUsername", "buyer"));
    }

    private static EmailJob versionJob(String key) {
        return EmailJob.of(EmailKind.NEW_VERSION, "seller", "RELEASE:" + key,
                Map.of("listingId", "listing", "listingTitle", "Monitor", "versionNumber", "2.0"));
    }

    private void publish(EmailJob job) throws Exception {
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, RabbitConfig.EMAIL_SEND_ROUTING_KEY,
                new Message(mapper.writeValueAsBytes(job), new MessageProperties()));
    }

    private EmailDispatch dispatch(EmailJob job) {
        return mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class);
    }

    private Conversation conversation(EmailJob job) {
        return mongo.findById(job.data().get("conversationId"), Conversation.class);
    }

    private void awaitStatus(EmailJob job, Status status) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(dispatch(job)).isNotNull()
                .satisfies(dispatch -> assertThat(dispatch.status()).isEqualTo(status)));
    }

    private static SendEmailResponse accepted(String id) {
        return SendEmailResponse.builder().messageId(id).build();
    }

    private static final class BlockingSend {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        SendEmailResponse send() throws InterruptedException {
            entered.countDown();
            assertThat(released.await(15, TimeUnit.SECONDS)).as("SES fake released before its timeout").isTrue();
            return accepted("blocked-id");
        }

        void awaitEntry() throws InterruptedException {
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("A sender entered the SES fake").isTrue();
        }

        void release() {
            released.countDown();
        }
    }
}
