package it.rebyte.user.dto;

/**
 * {@code GET /internal/users/{sub}} (D-51). One contract designed once,
 * reused by Phases 2 through 5 for seller reputation, buyer and seller
 * lookups and display names — rather than three similar shapes accreting.
 *
 * <p>For a never-provisioned {@code sub} this is <b>synthesized</b> (D-52):
 * the sub, a deterministic generated handle, a {@code null} avatar, a
 * {@code false} developer flag, a {@code null} rating average and a zero
 * rating count — computed, never persisted. See {@link
 * it.rebyte.user.service.UserSummaryService} for why that synthesized
 * summary is byte-identical to the row just-in-time provisioning would
 * create.
 *
 * <p>{@code ratingAvg}/{@code ratingCount} come from plan 01-03's
 * per-request aggregate over the review projection (D-48) — never a stored
 * column. {@code completedSales} is counted per request from the local
 * delivered and non-refunded sale projection.
 */
public record UserSummary(
        String sub,
        String username,
        String avatar,
        boolean isDeveloper,
        Double ratingAvg,
        long ratingCount,
        long completedSales) {
}
