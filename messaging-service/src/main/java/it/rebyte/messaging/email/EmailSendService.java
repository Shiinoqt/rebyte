package it.rebyte.messaging.email;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import it.rebyte.messaging.client.UserEmail;
import it.rebyte.messaging.client.UserServiceClient;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.EmailDispatch;
import it.rebyte.messaging.domain.EmailDispatch.Status;
import it.rebyte.messaging.repository.ConversationRepository;
import it.rebyte.messaging.service.EmailPreferenceService;
import it.rebyte.messaging.service.MongoTx;

@Service
public class EmailSendService {
    private static final Logger log = LoggerFactory.getLogger(EmailSendService.class);
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int COMPLETION_ATTEMPTS = 5;
    private final MongoTemplate mongo;
    private final MongoTx tx;
    private final EmailPreferenceService preferences;
    private final ConversationRepository conversations;
    private final UserServiceClient users;
    private final SuppressionService suppressions;
    private final EmailComposer composer;
    private final EmailRateGate gate;
    private final SesEmailClient ses;
    private final Duration window;
    private final Duration preparationTimeout;

    public EmailSendService(MongoTemplate mongo, MongoTx tx, EmailPreferenceService preferences,
            ConversationRepository conversations, UserServiceClient users, SuppressionService suppressions,
            EmailComposer composer, EmailRateGate gate, SesEmailClient ses,
            @Value("${rebyte.email.new-message-window}") Duration window,
            @Value("${rebyte.email.preparation-timeout:PT5M}") Duration preparationTimeout) {
        this.mongo = mongo;
        this.tx = tx;
        this.preferences = preferences;
        this.conversations = conversations;
        this.users = users;
        this.suppressions = suppressions;
        this.composer = composer;
        this.gate = gate;
        this.ses = ses;
        this.window = window;
        if (preparationTimeout.isNegative() || preparationTimeout.isZero()) {
            throw new IllegalStateException("Email preparation timeout must be positive");
        }
        this.preparationTimeout = preparationTimeout;
    }

    public void process(EmailJob job) {
        String id = EmailDispatch.idFor(job);
        try {
            mongo.insert(EmailDispatch.pending(job, now()));
        } catch (DuplicateKeyException duplicate) {
            // A duplicate must be caught outside a transaction, which Mongo would otherwise abort.
        }
        EmailDispatch existing = mongo.findById(id, EmailDispatch.class);
        if (existing != null && existing.status().isTerminal()) {
            return;
        }
        if (existing != null && (existing.status() == Status.SENDING || existing.status() == Status.DELIVERY_UNKNOWN)) {
            throw unresolved(job, id);
        }
        Claim claim = tx.execute(() -> claim(job, id));
        if (claim == null) {
            return;
        }
        String address;
        ComposedEmail email;
        try {
            Status skip = skip(job, claim);
            if (skip != null) {
                finish(job, claim, Status.PREPARING, skip, null);
                return;
            }
            UserEmail recipient;
            try {
                recipient = users.email(job.recipientSub());
            } catch (RuntimeException failure) {
                if (!notFound(failure)) {
                    throw failure;
                }
                recipient = null;
            }
            if (recipient == null || !recipient.verified() || recipient.email() == null || recipient.email().isBlank()) {
                finish(job, claim, Status.PREPARING, Status.SKIPPED_NO_ADDRESS, null);
                return;
            }
            address = recipient.email();
            if (suppressions.isSuppressed(address)) {
                finish(job, claim, Status.PREPARING, Status.SKIPPED_SUPPRESSED, null);
                return;
            }
            email = composer.compose(job);
            gate.acquire();
            long matched = mongo.updateFirst(owned(claim, Status.PREPARING), new Update().set("status", Status.SENDING)
                    .set("sendStartedAt", now()).set("updatedAt", now()), EmailDispatch.class).getMatchedCount();
            if (matched != 1) {
                throw new IllegalStateException("Email dispatch ownership changed before send");
            }
        } catch (RuntimeException failure) {
            try {
                release(claim);
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }

        String messageId;
        try {
            messageId = ses.send(address, email);
        } catch (AwsServiceException refusal) {
            String code = refusal.awsErrorDetails() == null ? null : refusal.awsErrorDetails().errorCode();
            log.warn("Email kind={} dispatch={} refusalCode={} refusalStatus={}",
                    job.kind(), id, code, refusal.statusCode());
            if ("MessageRejected".equals(code) || "MessageRejectedException".equals(code)) {
                finish(job, claim, Status.SENDING, Status.FAILED_PERMANENT, null);
                return;
            }
            if (refusal.statusCode() > 0) {
                // Provider exception messages can contain addresses, so retain only code and status.
                IllegalStateException failure = new IllegalStateException("Email service refused delivery: code="
                        + code + " status=" + refusal.statusCode());
                try {
                    release(claim);
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
                throw failure;
            }
            unknown(job, claim);
            throw unresolved(job, id);
        } catch (RuntimeException transportFailure) {
            unknown(job, claim);
            throw unresolved(job, id);
        }
        // This retry boundary contains only Mongo work. An accepted SES call must never be repeated.
        for (int attempt = 1; ; attempt++) {
            try {
                finish(job, claim, Status.SENDING, Status.SENT, messageId);
                return;
            } catch (DataAccessException | TransactionException persistenceFailure) {
                if (attempt == COMPLETION_ATTEMPTS) {
                    throw unresolved(job, id);
                }
                pauseCompletion();
            }
        }
    }

    @Scheduled(fixedDelayString = "${rebyte.email.preparation-reaper.fixed-delay-ms:60000}")
    public void reclaimStalePreparations() {
        Instant cutoff = now().minus(preparationTimeout);
        for (EmailDispatch dispatch : mongo.find(Query.query(Criteria.where("status").is(Status.PREPARING)
                .and("preparationStartedAt").lt(cutoff)).limit(100), EmailDispatch.class)) {
            tx.run(() -> {
                Query staleOwner = Query.query(Criteria.where("_id").is(dispatch.id()).and("status").is(Status.PREPARING)
                        .and("ownerToken").is(dispatch.ownerToken()).and("preparationStartedAt").lt(cutoff));
                long matched = mongo.updateFirst(staleOwner, new Update().set("status", Status.PENDING)
                        .set("updatedAt", now()).unset("ownerToken").unset("preparationStartedAt"),
                        EmailDispatch.class).getMatchedCount();
                if (matched == 1) {
                    // Dispatches do not store conversation ids; the reservation identifies the matching slots.
                    for (String slot : new String[] {"buyerEmailDispatchId", "sellerEmailDispatchId"}) {
                        mongo.updateMulti(Query.query(Criteria.where(slot).is(dispatch.id())),
                                new Update().unset(slot), Conversation.class);
                    }
                }
            });
        }
    }

    private Claim claim(EmailJob job, String id) {
        String owner = UUID.randomUUID().toString();
        EmailDispatch dispatch = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)
                        .and("status").is(Status.PENDING)), new Update().set("status", Status.PREPARING)
                        .set("ownerToken", owner).set("preparationStartedAt", now()).set("updatedAt", now()),
                FindAndModifyOptions.options().returnNew(true), EmailDispatch.class);
        if (dispatch == null) {
            EmailDispatch current = mongo.findById(id, EmailDispatch.class);
            if (current != null && current.status().isTerminal()) {
                return null;
            }
            if (current != null && (current.status() == Status.SENDING || current.status() == Status.DELIVERY_UNKNOWN)) {
                throw unresolved(job, id);
            }
            throw new IllegalStateException("Email dispatch is owned by another sender");
        }
        Conversation conversation = job.kind() == EmailKind.NEW_MESSAGE
                ? conversations.findById(job.data().get("conversationId")).orElse(null) : null;
        if (conversation == null || !conversation.isParticipant(job.recipientSub())) {
            return new Claim(id, owner, null, null, null, 0);
        }
        String prefix = conversation.getBuyerSub().equals(job.recipientSub()) ? "buyer" : "seller";
        String slot = prefix + "EmailDispatchId";
        Conversation reserved = mongo.findAndModify(Query.query(Criteria.where("_id").is(conversation.getId())
                        .andOperator(new Criteria().orOperator(Criteria.where(slot).isNull(), Criteria.where(slot).is(id)))),
                new Update().set(slot, id), FindAndModifyOptions.options().returnNew(true), Conversation.class);
        if (reserved == null) {
            throw new IllegalStateException("Conversation email slot is owned by another dispatch");
        }
        long generation = prefix.equals("buyer") ? reserved.getBuyerEmailGeneration() : reserved.getSellerEmailGeneration();
        return new Claim(id, owner, reserved, slot, prefix + "EmailGeneration", generation);
    }

    private Status skip(EmailJob job, Claim claim) {
        if (!job.kind().isTransactional() && !preferences.isEnabled(job.recipientSub())) {
            return Status.SKIPPED_OPT_OUT;
        }
        if (job.kind() != EmailKind.NEW_MESSAGE) {
            return null;
        }
        Conversation conversation = claim.conversation();
        if (conversation == null || conversation.unreadFor(job.recipientSub()) == 0) {
            return Status.SKIPPED_READ;
        }
        Instant last = conversation.getBuyerSub().equals(job.recipientSub())
                ? conversation.getBuyerLastEmailAt() : conversation.getSellerLastEmailAt();
        return last != null && last.plus(window).isAfter(now()) ? Status.SKIPPED_THROTTLED : null;
    }

    private void finish(EmailJob job, Claim claim, Status from, Status outcome, String messageId) {
        tx.run(() -> {
            Instant stamp = now();
            Update update = new Update().set("status", outcome).set("updatedAt", stamp)
                    .set("expiresAt", stamp.plus(RETENTION)).unset("ownerToken");
            if (messageId != null) {
                update.set("sesMessageId", messageId);
            }
            long matched = mongo.updateFirst(owned(claim, from), update, EmailDispatch.class).getMatchedCount();
            if (matched != 1) {
                EmailDispatch current = mongo.findById(claim.id(), EmailDispatch.class);
                if (current != null && current.status() == outcome
                        && (messageId == null || messageId.equals(current.sesMessageId()))) {
                    return;
                }
                throw new IllegalStateException("Email dispatch completion lost ownership");
            }
            if (claim.conversation() != null) {
                if (outcome == Status.SENT) {
                    mongo.updateFirst(Query.query(Criteria.where("_id").is(claim.conversation().getId())
                                    .and(claim.slot()).is(claim.id()).and(claim.generationField()).is(claim.generation())),
                            new Update().set(Conversation.lastEmailField(claim.conversation(), job.recipientSub()), stamp),
                            Conversation.class);
                }
                releaseSlot(claim);
            }
        });
        log.info("Email kind={} dispatch={} outcome={}", job.kind(), claim.id(), outcome);
    }

    private void release(Claim claim) {
        tx.run(() -> {
            Query query = Query.query(Criteria.where("_id").is(claim.id()).and("ownerToken").is(claim.owner())
                    .and("status").in(Status.PREPARING, Status.SENDING));
            long matched = mongo.updateFirst(query, new Update().set("status", Status.PENDING).set("updatedAt", now())
                    .unset("ownerToken").unset("preparationStartedAt").unset("sendStartedAt").unset("expiresAt"),
                    EmailDispatch.class).getMatchedCount();
            if (matched == 1 && claim.conversation() != null) {
                releaseSlot(claim);
            }
        });
    }

    private void releaseSlot(Claim claim) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(claim.conversation().getId())
                .and(claim.slot()).is(claim.id())), new Update().unset(claim.slot()), Conversation.class);
    }

    private void unknown(EmailJob job, Claim claim) {
        try {
            tx.run(() -> {
                long matched = mongo.updateFirst(owned(claim, Status.SENDING),
                        new Update().set("status", Status.DELIVERY_UNKNOWN).set("updatedAt", now()).unset("expiresAt"),
                        EmailDispatch.class).getMatchedCount();
                if (matched == 1 && claim.conversation() != null) {
                    releaseSlot(claim);
                }
            });
        } catch (RuntimeException unavailable) {
            // The durable SENDING owner and slot still fence replay if recording uncertainty fails.
        }
        log.info("Email kind={} dispatch={} outcome={}", job.kind(), claim.id(), Status.DELIVERY_UNKNOWN);
    }

    private static Query owned(Claim claim, Status status) {
        return Query.query(Criteria.where("_id").is(claim.id()).and("ownerToken").is(claim.owner())
                .and("status").is(status));
    }

    private static boolean notFound(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FeignException feign && feign.status() == 404) {
                return true;
            }
        }
        return false;
    }

    private static AmqpRejectAndDontRequeueException unresolved(EmailJob job, String id) {
        return new AmqpRejectAndDontRequeueException("Email kind=" + job.kind() + " dispatch=" + id
                + " outcome=RECONCILIATION_REQUIRED");
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void pauseCompletion() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AmqpRejectAndDontRequeueException("Email completion interrupted; reconciliation required");
        }
    }

    private record Claim(String id, String owner, Conversation conversation, String slot, String generationField,
            long generation) {
    }
}
