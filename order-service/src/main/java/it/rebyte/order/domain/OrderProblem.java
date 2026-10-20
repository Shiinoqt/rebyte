package it.rebyte.order.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_problem")
public class OrderProblem {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "order_id", length = 36, nullable = false, updatable = false)
    private String orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32, nullable = false, updatable = false)
    private ProblemReason reason;

    @Column(name = "description", length = 2000, nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private ProblemStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private ProblemOutcome outcome;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 1000)
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected OrderProblem() {
    }

    public OrderProblem(String id, String orderId, ProblemReason reason, String description, Instant createdAt) {
        this.id = id;
        this.orderId = orderId;
        this.reason = reason;
        this.description = description;
        this.status = ProblemStatus.OPEN;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public ProblemReason getReason() { return reason; }
    public String getDescription() { return description; }
    public ProblemStatus getStatus() { return status; }
    public ProblemOutcome getOutcome() { return outcome; }
    public String getResolvedBy() { return resolvedBy; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
