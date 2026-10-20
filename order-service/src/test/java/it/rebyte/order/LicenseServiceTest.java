package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import it.rebyte.order.domain.License;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.repository.LicenseRepository;
import it.rebyte.order.service.LicenseService;

@ExtendWith(MockitoExtension.class)
class LicenseServiceTest {

    @Mock
    private LicenseRepository licenses;

    @InjectMocks
    private LicenseService service;

    @Test
    void retriesFourExistingCandidatesAndSavesTheFifth() {
        Order order = softwareOrder("ACME-################");
        when(licenses.findByOrderId(order.getId())).thenReturn(Optional.empty());
        AtomicInteger probes = new AtomicInteger();
        when(licenses.existsById(any())).thenAnswer(call -> probes.incrementAndGet() < 5);
        when(licenses.save(any())).thenAnswer(call -> call.getArgument(0));

        License issued = service.issue(order, Instant.now());

        assertThat(probes).hasValue(5);
        assertThat(issued.getKey()).matches("ACME-[0-9A-HJKMNP-TV-Z]{16}");
        verify(licenses).save(any(License.class));
    }

    @Test
    void givesUpAfterFiveCollisionsWithoutSavingOrLeakingCandidate() {
        Order order = softwareOrder("ACME-################");
        when(licenses.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(licenses.existsById(any())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(order, Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(order.getId())
                .hasMessageNotContaining("ACME-");
        verify(licenses, never()).save(any());
    }

    @Test
    void invalidSnapshotFallsBackToDefaultPattern() {
        Order order = softwareOrder("ABC");
        when(licenses.findByOrderId(order.getId())).thenReturn(Optional.empty());
        when(licenses.existsById(any())).thenReturn(false);
        ArgumentCaptor<License> saved = ArgumentCaptor.forClass(License.class);
        when(licenses.save(saved.capture())).thenAnswer(call -> call.getArgument(0));

        service.issue(order, Instant.now());

        assertThat(saved.getValue().getKey()).matches("RBYT-[0-9A-HJKMNP-TV-Z]{5}(?:-[0-9A-HJKMNP-TV-Z]{5}){3}");
    }

    private Order softwareOrder(String pattern) {
        Order order = new Order("order-1", "buyer", "seller", "listing-1", ListingType.SOFTWARE, "Title",
                new BigDecimal("1.00"), BigDecimal.ZERO, new BigDecimal("1.00"), PaymentMethod.PAYPAL,
                "idem", "fingerprint", Instant.now(), Instant.now().plusSeconds(900));
        order.snapshotLicenseKeyPattern(pattern);
        return order;
    }
}
