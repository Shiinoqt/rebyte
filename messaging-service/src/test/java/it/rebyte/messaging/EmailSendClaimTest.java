package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import it.rebyte.messaging.client.UserEmail;
import it.rebyte.messaging.client.UserServiceClient;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.EmailDispatch;
import it.rebyte.messaging.domain.EmailDispatch.Status;
import it.rebyte.messaging.email.*;
import it.rebyte.messaging.repository.ConversationRepository;
import it.rebyte.messaging.service.EmailPreferenceService;
import it.rebyte.messaging.service.MongoTx;

@ExtendWith(OutputCaptureExtension.class)
class EmailSendClaimTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoTx tx = mock(MongoTx.class);
    private final EmailPreferenceService preferences = mock(EmailPreferenceService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final UserServiceClient users = mock(UserServiceClient.class);
    private final SuppressionService suppressions = mock(SuppressionService.class);
    private final EmailComposer composer = mock(EmailComposer.class);
    private final EmailRateGate gate = mock(EmailRateGate.class);
    private final SesEmailClient ses = mock(SesEmailClient.class);
    private EmailSendService service;
    private final EmailJob job = EmailJob.of(EmailKind.NEW_MESSAGE, "seller", "MESSAGE:1",
            Map.of("conversationId", "conversation", "listingTitle", "Monitor", "senderUsername", "buyer"));

    @BeforeEach
    void setup() {
        service = new EmailSendService(mongo, tx, preferences, conversations, users, suppressions, composer, gate,
                ses, Duration.ofMinutes(15), Duration.ofMinutes(5));
        lenient().when(tx.execute(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
        lenient().doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(tx).run(any());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"SENT", "SKIPPED_OPT_OUT", "SKIPPED_READ", "SKIPPED_THROTTLED",
            "SKIPPED_NO_ADDRESS", "SKIPPED_SUPPRESSED", "FAILED_PERMANENT"})
    void terminalDispatchAcknowledgesWithoutSending(Status status) {
        when(mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class)).thenReturn(dispatch(status));
        service.process(job);
        verifyNoInteractions(ses, users, gate);
        verify(mongo, never()).findAndModify(any(), any(), any(FindAndModifyOptions.class), eq(EmailDispatch.class));
    }

    @Test
    void casLoserCannotSendOrReleaseAnotherOwner() {
        when(mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class)).thenReturn(dispatch(Status.PENDING),
                dispatch(Status.PREPARING));
        assertThatThrownBy(() -> service.process(job)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ses, users);
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(EmailDispatch.class));
    }

    @Test
    void definiteRefusalReleasesOwnerAndPairWithoutSuccessStamp() {
        ready();
        when(ses.send(any(), any())).thenThrow(SesV2Exception.builder().statusCode(429)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("TooManyRequestsException").build()).build());
        assertThatThrownBy(() -> service.process(job)).isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<Update> dispatchUpdate = ArgumentCaptor.forClass(Update.class);
        verify(mongo, atLeastOnce()).updateFirst(any(), dispatchUpdate.capture(), eq(EmailDispatch.class));
        assertThat(dispatchUpdate.getAllValues()).anySatisfy(update -> {
            assertThat(update.getUpdateObject().get("$set", org.bson.Document.class).get("status"))
                    .isEqualTo(Status.PENDING);
            assertThat(update.getUpdateObject().get("$unset", org.bson.Document.class))
                    .containsKey("ownerToken");
        });
        ArgumentCaptor<Update> pairUpdate = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(any(), pairUpdate.capture(), eq(Conversation.class));
        assertThat(pairUpdate.getValue().getUpdateObject().get("$unset", org.bson.Document.class))
                .containsKey("sellerEmailDispatchId");
        assertThat(pairUpdate.getValue().getUpdateObject().toJson()).doesNotContain("sellerLastEmailAt");
    }

    @Test
    void uncertainAcceptanceIsDurableAndCannotResend() {
        ready();
        when(ses.send(any(), any())).thenThrow(SdkClientException.create("connection closed"));
        assertThatThrownBy(() -> service.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongo, atLeastOnce()).updateFirst(any(), updates.capture(), eq(EmailDispatch.class));
        assertThat(updates.getAllValues()).anySatisfy(update -> assertThat(
                update.getUpdateObject().get("$set", org.bson.Document.class).get("status"))
                .isEqualTo(Status.DELIVERY_UNKNOWN));
        verify(mongo).updateFirst(any(), any(Update.class), eq(Conversation.class));
        when(mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class)).thenReturn(dispatch(Status.DELIVERY_UNKNOWN));
        assertThatThrownBy(() -> service.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(ses, times(1)).send(any(), any());
    }

    @Test
    void sendingCannotBeStolenEvenWithAnOldTimestamp() {
        when(mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class)).thenReturn(dispatch(Status.SENDING));
        assertThatThrownBy(() -> service.process(job)).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(ses);
    }

    @Test
    void failedReleaseDoesNotMaskPreparationFailure() {
        ready();
        RuntimeException original = new IllegalStateException("directory unavailable");
        RuntimeException releaseFailure = new IllegalStateException("Mongo unavailable");
        when(users.email("seller")).thenThrow(original);
        when(mongo.updateFirst(any(), any(Update.class), eq(EmailDispatch.class))).thenThrow(releaseFailure);
        assertThatThrownBy(() -> service.process(job)).isSameAs(original)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(releaseFailure));
        verifyNoInteractions(ses);
    }

    @Test
    void badRequestIsRetryableAndKeepsSafeProviderDetails(CapturedOutput output) {
        ready();
        when(ses.send(any(), any())).thenThrow(SesV2Exception.builder().statusCode(400)
                .message("private-recipient@example.test")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("BadRequestException").build()).build());
        assertThatThrownBy(() -> service.process(job)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BadRequestException").hasMessageContaining("400")
                .hasMessageNotContaining("private-recipient@example.test").hasCause(null);
        assertThat(output).contains("refusalCode=BadRequestException", "refusalStatus=400")
                .doesNotContain("private-recipient@example.test");
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongo, atLeastOnce()).updateFirst(any(), updates.capture(), eq(EmailDispatch.class));
        assertThat(updates.getAllValues()).anySatisfy(update -> assertThat(
                update.getUpdateObject().get("$set", org.bson.Document.class).get("status"))
                .isEqualTo(Status.PENDING));
    }

    @Test
    void stalePreparationReclaimUsesOwnerStatusAndLeaseAndReleasesMatchingSlots() {
        EmailDispatch stale = dispatch(Status.PREPARING);
        when(mongo.find(any(Query.class), eq(EmailDispatch.class))).thenReturn(java.util.List.of(stale));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(EmailDispatch.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        service.reclaimStalePreparations();
        ArgumentCaptor<Query> selection = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(selection.capture(), eq(EmailDispatch.class));
        assertThat(selection.getValue().getQueryObject()).containsEntry("status", Status.PREPARING);
        Instant cutoff = (Instant) selection.getValue().getQueryObject()
                .get("preparationStartedAt", org.bson.Document.class).get("$lt");
        assertThat(cutoff).isBetween(Instant.now().minusSeconds(302), Instant.now().minusSeconds(299));
        ArgumentCaptor<Query> cas = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).updateFirst(cas.capture(), update.capture(), eq(EmailDispatch.class));
        assertThat(cas.getValue().getQueryObject()).containsEntry("_id", stale.id())
                .containsEntry("status", Status.PREPARING).containsEntry("ownerToken", stale.ownerToken());
        assertThat(update.getValue().getUpdateObject().get("$set", org.bson.Document.class))
                .containsEntry("status", Status.PENDING);
        assertThat(update.getValue().getUpdateObject().get("$unset", org.bson.Document.class))
                .containsKeys("ownerToken", "preparationStartedAt");
        ArgumentCaptor<Query> slots = ArgumentCaptor.forClass(Query.class);
        verify(mongo, times(2)).updateMulti(slots.capture(), any(Update.class), eq(Conversation.class));
        assertThat(slots.getAllValues()).anySatisfy(query -> assertThat(query.getQueryObject())
                .containsEntry("buyerEmailDispatchId", stale.id())).anySatisfy(query -> assertThat(query.getQueryObject())
                .containsEntry("sellerEmailDispatchId", stale.id()));
        verifyNoInteractions(ses);
    }

    @Test
    void failedReclaimCasCannotReleaseSlots() {
        when(mongo.find(any(Query.class), eq(EmailDispatch.class))).thenReturn(java.util.List.of(dispatch(Status.PREPARING)));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(EmailDispatch.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        service.reclaimStalePreparations();
        verify(mongo, never()).updateMulti(any(Query.class), any(Update.class), eq(Conversation.class));
    }

    private void ready() {
        when(mongo.findById(EmailDispatch.idFor(job), EmailDispatch.class)).thenReturn(dispatch(Status.PENDING));
        when(mongo.findAndModify(any(), any(), any(FindAndModifyOptions.class), eq(EmailDispatch.class)))
                .thenAnswer(call -> {
                    String owner = ((Update) call.getArgument(1)).getUpdateObject()
                            .get("$set", org.bson.Document.class).getString("ownerToken");
                    return new EmailDispatch(EmailDispatch.idFor(job), job.kind(), job.recipientSub(), job.dedupKey(),
                            Status.PREPARING, owner, Instant.now(), null, null, Instant.now(), Instant.now(), null);
                });
        Conversation conversation = new Conversation("conversation", "listing", "HARDWARE", "buyer", "seller",
                "Monitor", BigDecimal.TEN, Instant.now(), Instant.now(), null, 1, 0, 1, null, null,
                null, null, 0, 0);
        when(conversations.findById("conversation")).thenReturn(Optional.of(conversation));
        when(mongo.findAndModify(any(), any(), any(FindAndModifyOptions.class), eq(Conversation.class)))
                .thenReturn(conversation);
        when(mongo.updateFirst(any(), any(Update.class), eq(EmailDispatch.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        lenient().when(mongo.updateFirst(any(), any(Update.class), eq(Conversation.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        when(preferences.isEnabled("seller")).thenReturn(true);
        when(users.email("seller")).thenReturn(new UserEmail("seller", "seller@example.test", true));
        when(composer.compose(job)).thenReturn(new ComposedEmail("summary", "html", "text", "url"));
    }

    private EmailDispatch dispatch(Status status) {
        return new EmailDispatch(EmailDispatch.idFor(job), job.kind(), job.recipientSub(), job.dedupKey(), status,
                status == Status.PENDING ? null : "other-owner", Instant.EPOCH, Instant.EPOCH, null,
                Instant.EPOCH, Instant.EPOCH, null);
    }
}
