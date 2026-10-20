package it.rebyte.user.controller.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller for {@code OpenApiInternalExclusionIT} (DOC-01).
 *
 * <p>Lives in {@code it.rebyte.user.controller.internal} — the same package
 * every real internal controller uses — so the test can prove the springdoc
 * exclusion in {@code application.yml} is a package rule, not a list of
 * paths someone maintains: a brand new controller dropped into this package
 * must be absent from the generated document with zero changes to the
 * exclusion configuration itself.
 *
 * <p>Gated behind the {@code openapi-exclusion-throwaway} profile so it
 * exists only for the duration of the one test class that activates it —
 * Spring's default component scan (base package {@code it.rebyte.user})
 * picks this class up from the test classpath exactly like a production
 * controller, which is what makes the proof meaningful.
 */
@RestController
@Profile("openapi-exclusion-throwaway")
public class ThrowawayInternalController {

    @GetMapping("/internal/throwaway-test-controller")
    public String probe() {
        return "throwaway";
    }
}
