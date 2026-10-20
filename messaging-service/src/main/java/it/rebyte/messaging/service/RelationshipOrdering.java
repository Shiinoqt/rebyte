package it.rebyte.messaging.service;

import java.time.Instant;

public final class RelationshipOrdering {

    public enum Decision { APPLY, REPLAY, STALE, CONFLICT }

    public record State(String lastEventId, String lastEventTime, Long lastRelationshipSequence, boolean active) {}

    private RelationshipOrdering() {}

    public static Decision decide(State state, String eventId, Instant occurredAt, Long sequence, boolean active) {
        if (state == null) {
            return Decision.APPLY;
        }
        if (eventId.equals(state.lastEventId())) {
            return Decision.REPLAY;
        }
        if (sequence != null && state.lastRelationshipSequence() != null) {
            int order = Long.compare(sequence, state.lastRelationshipSequence());
            return order > 0 ? Decision.APPLY : order < 0 ? Decision.STALE : Decision.CONFLICT;
        }
        int order = occurredAt.compareTo(Instant.parse(state.lastEventTime()));
        if (order != 0) {
            return order > 0 ? Decision.APPLY : Decision.STALE;
        }
        // Legacy exact-time events cannot establish chronology. Removal wins,
        // but a legacy tie cannot override a state with producer sequence metadata.
        return state.lastRelationshipSequence() == null && state.active() && !active
                ? Decision.APPLY : Decision.STALE;
    }
}
