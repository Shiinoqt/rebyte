package it.rebyte.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.PersistenceCreator;

/**
 * Immutable, append-only entry in a listing's embedded price history.
 *
 * <p>The surrogate identifier is retained for durable object identity. The
 * notification deep-link uses the stable listing-and-instant pair, so both
 * {@link #instant} and the id must never be changed after creation.
 */
public class PriceChange {
    private final String id;
    private final Instant instant;
    private final BigDecimal oldPrice;
    private final BigDecimal newPrice;

    public PriceChange(Instant instant, BigDecimal oldPrice, BigDecimal newPrice) {
        this(UUID.randomUUID().toString(), instant, oldPrice, newPrice);
    }

    @PersistenceCreator
    public PriceChange(String id, Instant instant, BigDecimal oldPrice, BigDecimal newPrice) {
        this.id = id;
        this.instant = instant;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
    }

    public String getId() { return id; }
    public Instant getInstant() { return instant; }
    public BigDecimal getOldPrice() { return oldPrice; }
    public BigDecimal getNewPrice() { return newPrice; }

    /** Numeric comparison is intentional: BigDecimal subtraction is not a predicate. */
    public boolean isDrop() { return newPrice.compareTo(oldPrice) < 0; }
}
