package it.rebyte.user.controller.internal;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.user.dto.FollowersPage;
import it.rebyte.user.repository.FollowRepository;

/**
 * {@code GET /internal/users/{sub}/followers} (SOCL-05, D-40, D-46, D-56).
 * Lives in {@code it.rebyte.user.controller.internal} for the same reason
 * as {@link UserInternalController} — see that class's Javadoc.
 *
 * <p><b>Keyset pagination, not offset (D-40).</b> {@link
 * FollowRepository#findFollowerSubsPage} orders by {@code follower_sub}
 * ascending with a strict {@code >} comparison against the cursor: a
 * follower whose sub equals the cursor belongs to the earlier page and is
 * never returned a second time, because with the composite key there are
 * no ties to break — this is what makes the pagination deterministic even
 * while new follows are inserted between page fetches.
 *
 * <p>Page size defaults to {@value #DEFAULT_LIMIT}, hard-capped at
 * {@value #MAX_LIMIT}, clamped silently rather than rejected (D-46): a
 * consumer that asks for too much still makes progress instead of failing.
 */
@RestController
@RequestMapping("/internal/users")
public class FollowersInternalController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final FollowRepository followRepository;

    public FollowersInternalController(FollowRepository followRepository) {
        this.followRepository = followRepository;
    }

    @GetMapping("/{sub}/followers")
    public FollowersPage followers(
            @PathVariable String sub,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        int pageSize = clamp(limit);
        String effectiveCursor = cursor == null ? "" : cursor;

        // Over-fetch by one row to learn whether a next page exists, without
        // a second round trip — the extra row is trimmed below and never
        // returned to the caller; only its presence/absence is used.
        Pageable pageable = PageRequest.of(0, pageSize + 1);
        List<String> rows = followRepository.findFollowerSubsPage(sub, effectiveCursor, pageable);

        boolean hasMore = rows.size() > pageSize;
        List<String> page = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? page.get(page.size() - 1) : null;
        return new FollowersPage(page, nextCursor);
    }

    private static int clamp(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
