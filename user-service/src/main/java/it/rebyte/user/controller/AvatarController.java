package it.rebyte.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.user.domain.User;
import it.rebyte.user.dto.AvatarConfirmRequest;
import it.rebyte.user.dto.AvatarUploadPolicyResponse;
import it.rebyte.user.dto.MeResponse;
import it.rebyte.user.mapper.UserMapper;
import it.rebyte.user.service.AvatarService;
import it.rebyte.user.service.UserService;

import jakarta.validation.Valid;

/**
 * {@code /users/me/avatar/**} (USER-03, D-34 through D-39). Every endpoint
 * is owner-scoped exactly like the rest of {@code /users/me} (D-55): the
 * subject comes SOLELY from the authenticated {@link Jwt}, never from the
 * request body, so there is no URL that produces an upload policy or
 * accepts a confirm or removal for another user's prefix (T-06-06).
 */
@RestController
public class AvatarController {

    private final UserService userService;
    private final AvatarService avatarService;
    private final UserMapper userMapper;

    public AvatarController(UserService userService, AvatarService avatarService, UserMapper userMapper) {
        this.userService = userService;
        this.avatarService = avatarService;
        this.userMapper = userMapper;
    }

    @PostMapping("/users/me/avatar/upload-policy")
    public AvatarUploadPolicyResponse uploadPolicy(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveOrProvision(jwt);
        return avatarService.createUploadPolicy(user.getId());
    }

    @PostMapping("/users/me/avatar/confirm")
    public MeResponse confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AvatarConfirmRequest request) {
        User user = userService.resolveOrProvision(jwt);
        User updated = avatarService.confirmUpload(user, request.key());
        return userMapper.toMeResponse(updated);
    }

    @DeleteMapping("/users/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt) {
        User user = userService.resolveOrProvision(jwt);
        avatarService.removeAvatar(user);
    }
}
