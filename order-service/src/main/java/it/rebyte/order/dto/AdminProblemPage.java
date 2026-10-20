package it.rebyte.order.dto;

import java.time.Instant;
import java.util.List;

import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.ProblemOutcome;
import it.rebyte.order.domain.ProblemReason;
import it.rebyte.order.domain.ProblemStatus;

public record AdminProblemPage(List<Item> items, String nextCursor) {

    public record Item(String id, String orderId, ProblemReason reason, String description, ProblemStatus status,
            ProblemOutcome outcome, String resolvedBy, String resolutionNote, Instant createdAt, Instant resolvedAt) {

        public static Item from(OrderProblem problem) {
            return new Item(problem.getId(), problem.getOrderId(), problem.getReason(), problem.getDescription(),
                    problem.getStatus(), problem.getOutcome(), problem.getResolvedBy(), problem.getResolutionNote(),
                    problem.getCreatedAt(), problem.getResolvedAt());
        }
    }
}
