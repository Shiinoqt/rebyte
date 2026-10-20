package it.rebyte.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Retained after removal so a pair's transitions stay ordered across re-favouriting. */
@Document(collection = "favourite_transition_clock")
public class FavouriteTransitionClock {
    @Id private final FavouriteId id;
    private final long sequence;

    public FavouriteTransitionClock(FavouriteId id, long sequence) {
        this.id = id;
        this.sequence = sequence;
    }

    public FavouriteId getId() { return id; }
    public long getSequence() { return sequence; }
}
