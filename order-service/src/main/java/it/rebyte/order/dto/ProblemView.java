package it.rebyte.order.dto;

import java.time.Instant;

import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.ProblemOutcome;
import it.rebyte.order.domain.ProblemReason;
import it.rebyte.order.domain.ProblemStatus;

public record ProblemView(String id, String orderId, ProblemReason reason, String description, ProblemStatus status,
        ProblemOutcome outcome, String resolutionNote, Instant createdAt, Instant resolvedAt) {

    public static ProblemView from(OrderProblem problem) {
        return new ProblemView(problem.getId(), problem.getOrderId(), problem.getReason(), problem.getDescription(),
                problem.getStatus(), problem.getOutcome(), problem.getResolutionNote(), problem.getCreatedAt(),
                problem.getResolvedAt());
    }
}
