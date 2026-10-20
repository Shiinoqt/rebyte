package it.rebyte.messaging.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * D-47: starts empty, with no backfill. Favourites created before Phase 5
 * receive no price-drop notification until re-favourited.
 */
@Document(collection = "favourite_projection")
@CompoundIndex(name = "listing_active_user", def = "{'listingId': 1, 'active': 1, 'userSub': 1}")
public class FavouriteProjection {

    @Id
    private final String id;
    private final String userSub;
    private final String listingId;
    private final String sellerSub;
    private final boolean active;
    private final Instant lastEventAt;
    private final String lastEventTime;
    private final String lastEventId;
    private final Long lastRelationshipSequence;

    public FavouriteProjection(String id, String userSub, String listingId, String sellerSub, boolean active,
            Instant lastEventAt, String lastEventTime, String lastEventId, Long lastRelationshipSequence) {
        this.id = id;
        this.userSub = userSub;
        this.listingId = listingId;
        this.sellerSub = sellerSub;
        this.active = active;
        this.lastEventAt = lastEventAt;
        this.lastEventTime = lastEventTime;
        this.lastEventId = lastEventId;
        this.lastRelationshipSequence = lastRelationshipSequence;
    }

    public String getId() {
        return id;
    }

    public String getUserSub() {
        return userSub;
    }

    public String getListingId() {
        return listingId;
    }

    public String getSellerSub() {
        return sellerSub;
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
