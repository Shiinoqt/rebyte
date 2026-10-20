package it.rebyte.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.user.service.FollowService;

/**
 * {@code /users/{sub}/follow} (D-54, D-55, SOCL-01, SOCL-02). Follow and
 * unfollow nest under the single public {@code /users/**} prefix, using
 * distinct HTTP methods for the two directions of the relationship.
 *
 * <p>The follower is always the authenticated caller, resolved SOLELY from
 * the {@link Jwt} — exactly the {@link UserController} pattern. The path
 * variable is only ever the target of the (un)follow, never the acting
 * party: that keeps D-55's structural property intact, there is no URL
 * that lets one user act as another.
 *
 * <p>{@code GET /users/{sub}/followers} is deliberately NOT added here
 * (D-43): SOCL-05 asks only for the internal fan-out endpoint
 * ({@link it.rebyte.user.controller.internal.FollowersInternalController}),
 * and a public follow list is a recorded gap in the requirement set, not
 * something to invent now.
 */
@RestController
public class FollowController {

    private final FollowService followService;

    public FollowController(FollowService followService) {
        this.followService = followService;
    }

    @PutMapping("/users/{sub}/follow")
    public ResponseEntity<Void> follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String sub) {
        followService.follow(jwt.getSubject(), sub);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users/{sub}/follow")
    public ResponseEntity<Void> unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String sub) {
        followService.unfollow(jwt.getSubject(), sub);
        return ResponseEntity.noContent().build();
    }
}
