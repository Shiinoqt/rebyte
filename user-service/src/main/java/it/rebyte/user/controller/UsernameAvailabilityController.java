package it.rebyte.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.user.dto.UsernameAvailabilityResponse;
import it.rebyte.user.service.UsernameAvailabilityService;

/**
 * {@code GET /users/username-available?u=} (D-28). Anonymous by design and
 * deliberately without a {@code Jwt} parameter, so it can never provision a
 * user row. The literal path wins over {@code UserController}'s {@code
 * /users/{sub}}.
 *
 * <p>The answer is advisory: rule failures are answered with 200 too, and
 * the unique index behind {@code PUT /users/me} decides.
 */
@RestController
public class UsernameAvailabilityController {

    private final UsernameAvailabilityService usernameAvailabilityService;

    public UsernameAvailabilityController(UsernameAvailabilityService usernameAvailabilityService) {
        this.usernameAvailabilityService = usernameAvailabilityService;
    }

    @GetMapping("/users/username-available")
    public UsernameAvailabilityResponse check(@RequestParam("u") String u) {
        return usernameAvailabilityService.check(u);
    }
}
