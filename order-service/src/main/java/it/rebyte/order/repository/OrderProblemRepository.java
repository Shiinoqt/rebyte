package it.rebyte.order.repository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.ProblemOutcome;
import it.rebyte.order.domain.ProblemStatus;
import jakarta.persistence.LockModeType;

public interface OrderProblemRepository extends JpaRepository<OrderProblem, String> {
    Optional<OrderProblem> findByOrderId(String orderId);

    @Query("select p.orderId from OrderProblem p where p.id = :id")
    Optional<String> findOrderIdById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OrderProblem p where p.id = :id")
    Optional<OrderProblem> findByIdForUpdate(@Param("id") String id);

    boolean existsByOrderId(String orderId);
    boolean existsByOrderIdAndStatusIn(String orderId, Iterable<ProblemStatus> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update OrderProblem p set p.status = :withdrawn, p.resolvedAt = :now "
            + "where p.id = :problemId and p.status = :open")
    int withdraw(@Param("problemId") String problemId, @Param("now") Instant now,
            @Param("open") ProblemStatus open, @Param("withdrawn") ProblemStatus withdrawn);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update OrderProblem p set p.status = :pending, p.resolvedBy = :actorSub, "
            + "p.resolutionNote = :note where p.id = :problemId and p.status = :open")
    int claimRefund(@Param("problemId") String problemId, @Param("actorSub") String actorSub,
            @Param("note") String note, @Param("open") ProblemStatus open,
            @Param("pending") ProblemStatus pending);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update OrderProblem p set p.status = :open, p.resolvedBy = null, p.resolutionNote = null "
            + "where p.id = :problemId and p.status = :pending")
    int revertRefundClaim(@Param("problemId") String problemId, @Param("open") ProblemStatus open,
            @Param("pending") ProblemStatus pending);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update OrderProblem p set p.status = :resolved, p.outcome = :outcome, p.resolvedAt = :now "
            + "where p.orderId = :orderId and p.status in :statuses")
    int resolveByRefund(@Param("orderId") String orderId, @Param("now") Instant now,
            @Param("statuses") List<ProblemStatus> statuses, @Param("resolved") ProblemStatus resolved,
            @Param("outcome") ProblemOutcome outcome);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update OrderProblem p set p.status = :resolved, p.outcome = :outcome, p.resolvedBy = :actorSub, "
            + "p.resolutionNote = :note, p.resolvedAt = :now where p.id = :problemId and p.status = :open")
    int dismiss(@Param("problemId") String problemId, @Param("actorSub") String actorSub,
            @Param("note") String note, @Param("now") Instant now, @Param("open") ProblemStatus open,
            @Param("resolved") ProblemStatus resolved, @Param("outcome") ProblemOutcome outcome);

    @Query("select p from OrderProblem p where p.status in :statuses and "
            + "(:afterCreatedAt is null or p.createdAt > :afterCreatedAt "
            + "or (p.createdAt = :afterCreatedAt and p.id > :afterId)) "
            + "order by p.createdAt asc, p.id asc")
    List<OrderProblem> queue(@Param("statuses") List<ProblemStatus> statuses,
            @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") String afterId,
            Pageable pageable);

}
