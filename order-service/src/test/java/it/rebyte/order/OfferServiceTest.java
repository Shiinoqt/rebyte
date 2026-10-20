package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import java.math.BigDecimal;
import java.util.Set;
import java.sql.SQLIntegrityConstraintViolationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.dao.DataIntegrityViolationException;

import feign.FeignException;

import it.rebyte.commons.error.DomainException;
import it.rebyte.order.client.CatalogInternalClient;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.dto.CatalogListingResponse;
import it.rebyte.order.dto.CreateOfferRequest;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OfferRepository;
import it.rebyte.order.service.OfferService;

class OfferServiceTest {
    private final OfferRepository repository = mock(OfferRepository.class);
    private final DomainEventRecorder events = mock(DomainEventRecorder.class);
    private final CatalogInternalClient catalog = mock(CatalogInternalClient.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private OfferService service;

    @BeforeEach
    void setUp() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(repository.saveAndFlush(any(Offer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new OfferService(repository, events, catalog, transactions);
    }

    @Test
    void rejectsNonPositiveAmountBeforeCatalogLookup() {
        assertCode("invalid-offer-amount", "0.00");
        assertCode("invalid-offer-amount", "-0.01");
        verifyNoInteractions(catalog);
    }

    @Test
    void rejectsExcessPrecisionBeforeCatalogLookup() {
        assertCode("invalid-amount-scale", "80.001");
        verifyNoInteractions(catalog);
    }

    @Test
    void amountMustBeStrictlyBelowAskingPriceWithoutAdditionalFloor() {
        listing(ListingType.HARDWARE, "seller", true);
        assertCode("invalid-offer-amount", "120.00");
        assertCode("invalid-offer-amount", "120.01");
        assertThat(service.create("buyer", request("119.99")).amount()).isEqualByComparingTo("119.99");
        assertThat(service.create("buyer", request("0.01")).amount()).isEqualByComparingTo("0.01");
        assertThat(service.create("buyer", request("80.5")).amount()).isEqualByComparingTo("80.50");
    }

    @Test
    void rejectsSoftwareSelfOfferAndUnavailableListing() {
        listing(ListingType.SOFTWARE, "seller", true);
        assertCode("offer-hardware-only", "80.00");
        listing(ListingType.HARDWARE, "buyer", true);
        assertCode("self-offer-forbidden", "80.00");
        listing(ListingType.HARDWARE, "seller", false);
        assertCode("listing-unavailable", "80.00");
    }

    @Test
    void mapsMissingCatalogListingAndOnlyThePendingGuardConstraint() {
        when(catalog.availability("listing")).thenThrow(mock(FeignException.NotFound.class));
        assertCode("listing-unavailable", "80.00");

        listing(ListingType.HARDWARE, "seller", true);
        when(repository.saveAndFlush(any(Offer.class))).thenThrow(new DataIntegrityViolationException("duplicate",
                new SQLIntegrityConstraintViolationException("offers.uk_offer_buyer_pending_guard")));
        assertCode("duplicate-pending-offer", "80.00");
    }

    private void listing(ListingType type, String seller, boolean available) {
        doReturn(new CatalogListingResponse("listing", seller, "Amiga 500", new BigDecimal("120.00"),
                BigDecimal.ZERO, type, Set.of(PaymentMethod.PAYPAL), available, 1L))
                .when(catalog).availability("listing");
    }

    private void assertCode(String code, String amount) {
        DomainException error = catchThrowableOfType(DomainException.class,
                () -> service.create("buyer", request(amount)));
        assertThat(error).isNotNull();
        assertThat(error.getCode()).isEqualTo(code);
    }

    private CreateOfferRequest request(String amount) {
        return new CreateOfferRequest("listing", new BigDecimal(amount));
    }
}
