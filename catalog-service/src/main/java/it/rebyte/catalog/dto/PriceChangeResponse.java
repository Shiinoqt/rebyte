package it.rebyte.catalog.dto;
import java.math.BigDecimal;
import java.time.Instant;
import it.rebyte.catalog.domain.PriceChange;
public record PriceChangeResponse(Instant instant, BigDecimal oldPrice, BigDecimal newPrice, boolean drop) {
    public static PriceChangeResponse from(PriceChange c) { return new PriceChangeResponse(c.getInstant(), c.getOldPrice(), c.getNewPrice(), c.isDrop()); }
}
