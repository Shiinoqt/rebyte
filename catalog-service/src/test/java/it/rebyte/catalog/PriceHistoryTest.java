package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import it.rebyte.catalog.domain.PriceChange;

class PriceHistoryTest {

    @Test
    void dropIsDecidedByNumericComparisonRatherThanScaleSensitiveEquality() {
        PriceChange drop = new PriceChange(Instant.now(), new BigDecimal("10.00"), new BigDecimal("9.99"));
        PriceChange increase = new PriceChange(Instant.now(), new BigDecimal("9.990"), new BigDecimal("10.00"));

        assertThat(drop.isDrop()).isTrue();
        assertThat(increase.isDrop()).isFalse();
    }
}
