package it.rebyte.user.dto;

import java.util.List;

/**
 * {@code GET /internal/users/{sub}/followers} (SOCL-05, D-40). Bare {@code
 * sub} values only, plus a next-page cursor — no username, avatar or
 * instant. Pagination cannot be retrofitted without breaking a consumer
 * already written against this endpoint, so both properties are fixed in
 * this first version.
 *
 * <p>{@code nextCursor} is {@code null} when the page is the last one — an
 * empty follower set is an empty list, a {@code null} cursor and HTTP 200,
 * never a 404 and never a null body.
 */
public record FollowersPage(List<String> subs, String nextCursor) {
}
