package it.rebyte.user.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.user.domain.DeveloperProfile;
import it.rebyte.user.domain.User;
import it.rebyte.user.dto.DeveloperProfileResponse;
import it.rebyte.user.dto.MeResponse;
import it.rebyte.user.dto.PublicProfileResponse;
import it.rebyte.user.dto.UpdateProfileRequest;
import it.rebyte.user.mapper.UserMapper;
import it.rebyte.user.service.DeveloperProfileService;
import it.rebyte.user.service.UserProfileService;
import it.rebyte.user.service.UserService;

import jakarta.validation.Valid;

/**
 * {@code /users/**} (D-54, D-55, USER-01, USER-02, USER-04, USER-05).
 *
 * <p>The {@code sub} of the caller is resolved SOLELY from the
 * authenticated {@link Jwt} for every owner-scoped operation — never from a
 * {@code @PathVariable} or the request body. D-55 makes security
 * structural: there is no URL in this service that reads or edits another
 * user's profile as its owner, so there is no per-endpoint ownership
 * annotation whose single omission would be an IDOR.
 *
 * <p>{@code GET /users/{sub}} is the one deliberate exception: a PUBLIC
 * read of another person's profile (D-06, USER-04) — no ownership check by
 * design, because it is not owner-scoped.
 *
 * <p>{@code POST /users/me/developer-profile} (USER-05) always resolves the
 * caller's own row first (JIT-provisioning it if needed) before activating,
 * which is also what keeps {@code developer_profile}'s foreign key to
 * {@code user} safe — activation never runs before the owner's row exists.
 */
@RestController
public class UserController {

    private final UserService userService;
    private final UserProfileService userProfileService;
    private final DeveloperProfileService developerProfileService;
    private final UserMapper userMapper;

    public UserController(
            UserService userService,
            UserProfileService userProfileService,
            DeveloperProfileService developerProfileService,
            UserMapper userMapper) {
        this.userService = userService;
        this.userProfileService = userProfileService;
        this.developerProfileService = developerProfileService;
        this.userMapper = userMapper;
    }

    @GetMapping("/users/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveOrProvision(jwt);
        return userMapper.toMeResponse(user);
    }

    @PutMapping("/users/me")
    public MeResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
        User user = userService.resolveOrProvision(jwt);
        User updated = userProfileService.updateProfile(user, request);
        return userMapper.toMeResponse(updated);
    }

    @PostMapping("/users/me/developer-profile")
    public DeveloperProfileResponse activateDeveloperProfile(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveOrProvision(jwt);
        DeveloperProfile profile = developerProfileService.activate(user.getId());
        return userMapper.toDeveloperProfileResponse(profile);
    }

    @GetMapping("/users/{sub}")
    public PublicProfileResponse publicProfile(@PathVariable String sub, @AuthenticationPrincipal Jwt jwt) {
        return userProfileService.getPublicProfile(sub, jwt == null ? null : jwt.getSubject());
    }
}
