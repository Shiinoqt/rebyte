package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import it.rebyte.catalog.error.InvalidVersionNumberException;
import it.rebyte.catalog.service.VersionNumbers;

class VersionNumbersTest {
    @Test void comparesNumericComponentsNotText() {
        assertThat(VersionNumbers.compare("2.10.0", "2.9.0")).isPositive();
    }

    @Test void padsShorterNumbersAndAcceptsLeadingZeros() {
        assertThat(VersionNumbers.compare("1.0", "1")).isZero();
        assertThat(VersionNumbers.compare("01.02", "1.2.1")).isNegative();
    }

    @Test void rejectsMalformedAndPathologicalNumbers() {
        assertThatThrownBy(() -> VersionNumbers.compare("", "1")).isInstanceOf(InvalidVersionNumberException.class);
        assertThatThrownBy(() -> VersionNumbers.compare("1.x", "1")).isInstanceOf(InvalidVersionNumberException.class);
        assertThatThrownBy(() -> VersionNumbers.compare("1.2.3.4.5.6.7.8.9", "1"))
                .isInstanceOf(InvalidVersionNumberException.class);
    }
}
