package it.rebyte.order.messaging;

import java.time.Instant;
import java.util.UUID;

import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.ProblemOutcome;
import it.rebyte.order.domain.ProblemReason;
import it.rebyte.order.domain.ProblemStatus;

/** Problem notifications include the listing title for display; amounts, prices and addresses are never included. */
public record ProblemEvent(String eventId, String problemId, String orderId, String listingId, ListingType listingType,
        String buyerSub, String sellerSub, String listingTitle, ProblemReason reason, ProblemStatus status, ProblemOutcome outcome,
        Instant occurredAt) {

    public static ProblemEvent of(OrderProblem problem, Order order) {
        return new ProblemEvent(UUID.randomUUID().toString(), problem.getId(), order.getId(), order.getListingId(),
                order.getListingType(), order.getBuyerSub(), order.getSellerSub(), order.getListingTitle(), problem.getReason(),
                problem.getStatus(), problem.getOutcome(), Instant.now());
    }
}
