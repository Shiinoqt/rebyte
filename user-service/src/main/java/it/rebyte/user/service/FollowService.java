package it.rebyte.user.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.user.domain.Follow;
import it.rebyte.user.domain.FollowId;
import it.rebyte.user.error.SelfFollowException;
import it.rebyte.user.messaging.UserFollowedEvent;
import it.rebyte.user.messaging.UserUnfollowedEvent;
import it.rebyte.user.outbox.DomainEventRecorder;
import it.rebyte.user.repository.FollowRepository;
import it.rebyte.user.repository.FollowSequenceRepository;

/**
 * Follow and unfollow (SOCL-01, SOCL-02, D-17, D-19, D-42, D-44). Both are
 * idempotent and both return 204 whether or not state actually changed: a
 * retried request under at-least-once delivery must never surface an error
 * for an operation that already succeeded. The outbox row is written only
 * on a real state change (D-42): a repeat follow or a no-op unfollow writes
 * no event.
 *
 * <p>Every event leaves through {@link DomainEventRecorder} inside the SAME
 * transaction as the domain write (D-14, D-18, T-04-01); this class never
 * touches {@code RabbitTemplate} directly.
 *
 * <p>Each transition first locks the pair's {@code follow_transition_sequence}
 * row. Holding that lock before looking at the follow row serializes
 * concurrent requests for one pair, so "already following" is decided on
 * committed state and a duplicate-key race cannot occur. Every real
 * transition increments the pair counter and publishes it as
 * {@code relationshipSequence}; the counter survives unfollow, which makes it
 * the chronology consumers can rely on when {@code occurredAt} ties.
 *
 * <p>{@link TransactionTemplate} rather than {@code @Transactional} keeps the
 * transaction boundary explicit inside the method body: the follow row,
 * counter and outbox row commit or roll back together, and any failure
 * reaches the caller as the original exception.
 *
 * <p>Follows pointing at a sub deleted on the Cognito side are not filtered
 * (D-44): this service never queries Cognito and cannot know. Accepted
 * bounded cost: one unread notification per dead follower in Phase 5.
 */
@Service
public class FollowService {

    private final FollowRepository followRepository;
    private final FollowSequenceRepository followSequenceRepository;
    private final DomainEventRecorder domainEventRecorder;
    private final TransactionTemplate transactionTemplate;

    public FollowService(
            FollowRepository followRepository,
            FollowSequenceRepository followSequenceRepository,
            DomainEventRecorder domainEventRecorder,
            PlatformTransactionManager transactionManager) {
        this.followRepository = followRepository;
        this.followSequenceRepository = followSequenceRepository;
        this.domainEventRecorder = domainEventRecorder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Self-follow is refused before any transaction starts (SOCL-01, D-42;
     * {@code [V.Follow.noSelfFollow]}), so it surfaces as a clean {@link
     * SelfFollowException} (422).
     */
    public void follow(String followerSub, String followedSub) {
        if (followerSub.equals(followedSub)) {
            throw new SelfFollowException(followerSub);
        }

        transactionTemplate.executeWithoutResult(status -> {
            followSequenceRepository.lock(followerSub, followedSub);
            FollowId id = new FollowId(followerSub, followedSub);
            if (followRepository.existsById(id)) {
                return; // D-42: already following, idempotent 204, no outbox row
            }

            Instant instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
            followRepository.saveAndFlush(new Follow(id, instant));
            long sequence = followSequenceRepository.next(followerSub, followedSub);

            String eventId = UUID.randomUUID().toString();
            domainEventRecorder.record(
                    eventId,
                    RebyteExchanges.USER_FOLLOWED,
                    new UserFollowedEvent(
                            eventId, RebyteExchanges.USER_FOLLOWED, followerSub, followedSub, instant, sequence),
                    instant);
        });
    }

    public void unfollow(String followerSub, String followedSub) {
        transactionTemplate.executeWithoutResult(status -> {
            followSequenceRepository.lock(followerSub, followedSub);
            FollowId id = new FollowId(followerSub, followedSub);
            Optional<Follow> existing = followRepository.findById(id);
            if (existing.isEmpty()) {
                return; // D-42: already not following, idempotent 204, no outbox row
            }

            followRepository.delete(existing.get());
            long sequence = followSequenceRepository.next(followerSub, followedSub);

            Instant instant = Instant.now().truncatedTo(ChronoUnit.MICROS);
            String eventId = UUID.randomUUID().toString();
            domainEventRecorder.record(
                    eventId,
                    RebyteExchanges.USER_UNFOLLOWED,
                    new UserUnfollowedEvent(
                            eventId, RebyteExchanges.USER_UNFOLLOWED, followerSub, followedSub, instant, sequence),
                    instant);
        });
    }
}
