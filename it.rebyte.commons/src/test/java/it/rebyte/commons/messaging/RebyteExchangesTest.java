package it.rebyte.commons.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins every {@link RebyteExchanges} routing-key value (D-18, D-07, D-38, D-39). Hermetic —
 * no Spring context — because these are plain {@code static final String}
 * constants; a rename here must fail the build before any publisher or
 * consumer binds to the old value.
 */
class RebyteExchangesTest {

    @Test
    void offerTransitionKeysMatchD18() {
        assertThat(RebyteExchanges.OFFER_CREATED).isEqualTo("offer.created");
        assertThat(RebyteExchanges.OFFER_ACCEPTED).isEqualTo("offer.accepted");
        assertThat(RebyteExchanges.OFFER_REJECTED).isEqualTo("offer.rejected");
        assertThat(RebyteExchanges.OFFER_WITHDRAWN).isEqualTo("offer.withdrawn");
        assertThat(RebyteExchanges.OFFER_EXPIRED).isEqualTo("offer.expired");
    }

    @Test
    void offerTransitionKeysAreTwoWordsEach() {
        assertThat(RebyteExchanges.OFFER_CREATED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.OFFER_ACCEPTED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.OFFER_REJECTED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.OFFER_WITHDRAWN.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.OFFER_EXPIRED.split("\\.")).hasSize(2);
    }

    @Test
    void offerHoldReleasedKeyMatchesD07AndHasThreeWords() {
        assertThat(RebyteExchanges.OFFER_HOLD_RELEASED).isEqualTo("offer.hold.released");
        assertThat(RebyteExchanges.OFFER_HOLD_RELEASED.split("\\.")).hasSize(3);
    }

    @Test
    void phaseFourKeysMatchD38() {
        assertThat(RebyteExchanges.ORDER_PAID).isEqualTo("order.paid");
        assertThat(RebyteExchanges.ORDER_SHIPPED).isEqualTo("order.shipped");
        assertThat(RebyteExchanges.ORDER_DELIVERED).isEqualTo("order.delivered");
        assertThat(RebyteExchanges.ORDER_PROBLEM_OPENED).isEqualTo("order.problem.opened");
        assertThat(RebyteExchanges.ORDER_PROBLEM_RESOLVED).isEqualTo("order.problem.resolved");
        assertThat(RebyteExchanges.LICENCE_ISSUED).isEqualTo("licence.issued");
        assertThat(RebyteExchanges.LICENCE_REVOKED).isEqualTo("licence.revoked");
    }

    @Test
    void phaseFourKeyWordCountsKeepSingleWordBindingsAway() {
        assertThat(RebyteExchanges.ORDER_PAID.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.ORDER_SHIPPED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.ORDER_DELIVERED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.LICENCE_ISSUED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.LICENCE_REVOKED.split("\\.")).hasSize(2);
        assertThat(RebyteExchanges.ORDER_PROBLEM_OPENED.split("\\.")).hasSize(3);
        assertThat(RebyteExchanges.ORDER_PROBLEM_RESOLVED.split("\\.")).hasSize(3);
    }

    @Test
    void licenceKeysUseTheBritishSpelling() {
        String americanSpelling = "licen" + "se";
        assertThat(RebyteExchanges.LICENCE_ISSUED).doesNotContain(americanSpelling);
        assertThat(RebyteExchanges.LICENCE_REVOKED).doesNotContain(americanSpelling);
    }

    @Test
    void preExistingConstantsKeepTheirValues() {
        assertThat(RebyteExchanges.ORDER_CANCELLED).isEqualTo("order.cancelled");
        assertThat(RebyteExchanges.LISTING_UPDATED).isEqualTo("listing.updated");
    }
}
