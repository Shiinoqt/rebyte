package it.rebyte.messaging.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "follow_state")
public class FollowState {

    @Id
    private final String id;
    private final String followerSub;
    private final String followedSub;
    private final boolean active;
    private final Instant lastEventAt;
    private final String lastEventTime;
    private final String lastEventId;
    private final Long lastRelationshipSequence;

    public FollowState(String id, String followerSub, String followedSub, boolean active, Instant lastEventAt,
            String lastEventTime, String lastEventId, Long lastRelationshipSequence) {
        this.id = id;
        this.followerSub = followerSub;
        this.followedSub = followedSub;
        this.active = active;
        this.lastEventAt = lastEventAt;
        this.lastEventTime = lastEventTime;
        this.lastEventId = lastEventId;
        this.lastRelationshipSequence = lastRelationshipSequence;
    }

    public String getId() {
        return id;
    }

    public String getFollowerSub() {
        return followerSub;
    }

    public String getFollowedSub() {
        return followedSub;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public String getLastEventTime() {
        return lastEventTime;
    }

    public String getLastEventId() {
        return lastEventId;
    }

    public Long getLastRelationshipSequence() {
        return lastRelationshipSequence;
    }

}
