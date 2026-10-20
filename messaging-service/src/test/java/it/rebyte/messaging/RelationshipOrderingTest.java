package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static it.rebyte.messaging.service.RelationshipOrdering.Decision.*;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.messaging.messaging.FollowListener;
import it.rebyte.messaging.messaging.FavouriteListener;
import it.rebyte.messaging.service.FollowNotificationService;
import it.rebyte.messaging.service.FavouriteNotificationService;

import it.rebyte.messaging.service.RelationshipOrdering;
import it.rebyte.messaging.service.RelationshipOrdering.State;

class RelationshipOrderingTest {

    private static final Instant TIME = Instant.parse("2026-10-05T10:00:00.123456789Z");

    @Test
    void missingStateAcceptsCreationOrRemoval() {
        assertThat(RelationshipOrdering.decide(null, "create", TIME, null, true)).isEqualTo(APPLY);
        assertThat(RelationshipOrdering.decide(null, "remove", TIME, 2L, false)).isEqualTo(APPLY);
    }

    @Test
    void identicalIdIsReplayRegardlessOfOtherMetadata() {
        State state = new State("same", TIME.toString(), 2L, false);
        assertThat(RelationshipOrdering.decide(state, "same", TIME.plusSeconds(1), 3L, true))
                .isEqualTo(REPLAY);
        assertThat(RelationshipOrdering.decide(new State("same", TIME.toString(), null, true),
                "same", TIME, null, false)).isEqualTo(REPLAY);
    }

    @Test
    void lowerSequenceIsStaleEvenWithLaterTimestamp() {
        assertThat(RelationshipOrdering.decide(new State("last", TIME.toString(), 3L, true),
                "old", TIME.plusSeconds(10), 2L, false)).isEqualTo(STALE);
    }

    @Test
    void equalSequenceWithDifferentIdIsConflict() {
        assertThat(RelationshipOrdering.decide(new State("last", TIME.toString(), 2L, false),
                "different", TIME, 2L, true)).isEqualTo(CONFLICT);
    }

    @Test
    void higherSequencesApplyAtIdenticalOrEarlierTimestamp() {
        State state = new State("last", TIME.toString(), 2L, false);
        assertThat(RelationshipOrdering.decide(state, "new", TIME, 3L, true)).isEqualTo(APPLY);
        assertThat(RelationshipOrdering.decide(state, "new", TIME.minusSeconds(1), 3L, true)).isEqualTo(APPLY);
    }

    @Test
    void legacyOrderingPreservesNanosecondsWithinOneMillisecond() {
        State state = new State("last", TIME.toString(), null, true);
        assertThat(RelationshipOrdering.decide(state, "older", TIME.minusNanos(1), null, false)).isEqualTo(STALE);
        assertThat(RelationshipOrdering.decide(state, "later", TIME.plusNanos(1), null, false)).isEqualTo(APPLY);
        assertThat(RelationshipOrdering.decide(new State("removed", TIME.plusNanos(1).toString(), null, false),
                "old-create", TIME, null, true)).isEqualTo(STALE);
    }

    @Test
    void legacyExactTimeTieIsRemoveWinsWithoutUuidOrdering() {
        State active = new State("z-create", TIME.toString(), null, true);
        assertThat(RelationshipOrdering.decide(active, "a-remove", TIME, null, false)).isEqualTo(APPLY);
        assertThat(RelationshipOrdering.decide(active, "another-create", TIME, null, true)).isEqualTo(STALE);
        State removed = new State("a-remove", TIME.toString(), null, false);
        assertThat(RelationshipOrdering.decide(removed, "z-create", TIME, null, true)).isEqualTo(STALE);
        assertThat(RelationshipOrdering.decide(removed, "another-remove", TIME, null, false)).isEqualTo(STALE);
    }

    @Test
    void olderLegacyPayloadCannotOverwriteSequencedState() {
        State state = new State("sequenced", TIME.toString(), 3L, true);
        assertThat(RelationshipOrdering.decide(state, "legacy", TIME.minusNanos(1), null, false)).isEqualTo(STALE);
        assertThat(RelationshipOrdering.decide(state, "legacy", TIME, null, false)).isEqualTo(STALE);
        assertThat(RelationshipOrdering.decide(state, "later-legacy", TIME.plusNanos(1), null, false)).isEqualTo(APPLY);
    }

    @Test
    void firstSequencedPayloadStillRespectsExistingLegacyTime() {
        State state = new State("legacy", TIME.toString(), null, false);
        assertThat(RelationshipOrdering.decide(state, "old-sequenced", TIME.minusNanos(1), 1L, true)).isEqualTo(STALE);
        assertThat(RelationshipOrdering.decide(state, "new-sequenced", TIME.plusNanos(1), 1L, true)).isEqualTo(APPLY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1e3", "0", "-1", "true", "{}", "[]"})
    void followRejectsInvalidSequenceBeforeApplyingTransition(String sequence) {
        FollowListener listener = new FollowListener(new ObjectMapper().findAndRegisterModules(),
                mock(FollowNotificationService.class));
        String json = "{\"eventId\":\"invalid\",\"followerSub\":\"actor\",\"followedSub\":\"recipient\","
                + "\"occurredAt\":\"2026-10-05T10:00:00Z\",\"relationshipSequence\":" + sequence + "}";
        assertThatThrownBy(() -> listener.onEvent(message(json, "user.followed")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1e3", "0", "-1", "true", "{}", "[]"})
    void favouriteRejectsInvalidSequenceBeforeApplyingTransition(String sequence) {
        FavouriteListener listener = new FavouriteListener(new ObjectMapper().findAndRegisterModules(),
                mock(FavouriteNotificationService.class));
        String json = "{\"eventId\":\"invalid\",\"userSub\":\"actor\",\"sellerSub\":\"recipient\","
                + "\"listingId\":\"listing\",\"occurredAt\":\"2026-10-05T10:00:00Z\","
                + "\"relationshipSequence\":" + sequence + "}";
        assertThatThrownBy(() -> listener.onEvent(message(json, "listing.favourited")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    private static Message message(String json, String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }
}
