package it.rebyte.user.controller.internal;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.user.dto.UserSummary;
import it.rebyte.user.service.UserSummaryService;

/**
 * {@code GET /internal/users/{sub}} (D-51, D-52, D-56, D-69). Lives in
 * {@code it.rebyte.user.controller.internal}: the gateway denies the
 * {@code /internal/**} path prefix (D-56, plan 01-02) and springdoc
 * excludes this package (D-69) — a controller mapping this path from
 * anywhere else would be silently routable and silently published.
 *
 * <p><b>No authorization check — that is the design, not an omission.</b>
 * The internal surface trusts the internal network (docs/architecture.md
 * §7c); the package name is what makes that obvious in review.
 *
 * <p><b>Read-only, always.</b> Both endpoints return synthesized summaries
 * for never-provisioned subs (D-52), without writes or 404s. The batch read
 * accepts up to 50 distinct subs and preserves their first-seen order (D-42).
 */
@RestController
@RequestMapping("/internal/users")
public class UserInternalController {

    private final UserSummaryService userSummaryService;

    public UserInternalController(UserSummaryService userSummaryService) {
        this.userSummaryService = userSummaryService;
    }

    @GetMapping("/{sub}")
    public UserSummary summary(@PathVariable String sub) {
        return userSummaryService.getSummary(sub);
    }

    @GetMapping
    public List<UserSummary> summaries(@RequestParam("subs") List<String> subs) {
        return userSummaryService.getSummaries(subs);
    }
}
