package it.rebyte.user.domain;

import java.time.Instant;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * A follow relationship (SOCL-01, SOCL-02, D-41, D-45; ch. 10.6, {@code
 * Follow {instant}} on {@code follows}). Keyed by the embedded composite
 * {@link FollowId} — see that class for why a composite id rather than a
 * surrogate.
 *
 * <p>{@code instant} is stored and populated at creation but exposed in NO
 * response body this phase (D-45): the {@code user.followed} event's own
 * {@code occurredAt} carries the identical value to any consumer that needs
 * it, so no endpoint needs to return it. Its absence from every DTO in this
 * plan is this decision, not an oversight.
 *
 * <p><b>Implements {@link Persistable} (found via genuine RED evidence,
 * {@code FollowServiceIT}).</b> {@code Follow}'s id is manually assigned
 * (an {@code @EmbeddedId}, never {@code @GeneratedValue}), so Spring Data
 * JPA's default new-vs-existing heuristic — "is the id null?" — always says
 * "existing" for a freshly constructed instance, since the id is always
 * non-null here. Without this interface, {@code repository.save(...)} on a
 * brand-new {@code Follow} silently calls {@code entityManager.merge(...)}
 * instead of {@code persist(...)}: a REPEAT follow of an already-followed
 * pair then executes as a silent UPDATE of the existing row rather than
 * failing on the primary key, so {@code FollowService}'s
 * write-then-catch-conflict idempotency check (D-42) never fires and a
 * second outbox event is written for every repeat call. {@code isNew}
 * defaults {@code true} for every instance built via the public
 * constructor and flips to {@code false} once Hibernate has actually
 * persisted or loaded the row, which is exactly what forces {@code
 * save(...)} to always attempt a genuine {@code INSERT} for this entity.
 */
@Entity
@Table(name = "follow")
public class Follow implements Persistable<FollowId> {

    @EmbeddedId
    private FollowId id;

    @Column(name = "instant", nullable = false, updatable = false)
    private Instant instant;

    @Transient
    private boolean isNew = true;

    protected Follow() {
        // required by JPA
    }

    public Follow(FollowId id, Instant instant) {
        this.id = id;
        this.instant = instant;
    }

    @Override
    public FollowId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public Instant getInstant() {
        return instant;
    }
}
