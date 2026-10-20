package it.rebyte.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.dto.DismissProblemRequest;
import it.rebyte.order.dto.AdminProblemPage;
import it.rebyte.order.dto.ProblemView;
import it.rebyte.order.dto.ResolveProblemRequest;
import it.rebyte.order.service.ProblemResolutionService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/admin/problems")
public class AdminProblemController {

    private final ProblemResolutionService resolutions;

    public AdminProblemController(ProblemResolutionService resolutions) {
        this.resolutions = resolutions;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProblemPage queue(@RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return resolutions.queue(status, cursor, size);
    }

    @PostMapping("/{id}/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN')")
    public ProblemView refund(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody(required = false) ResolveProblemRequest request) {
        return resolutions.refundByAdmin(id, jwt.getSubject(), request == null ? null : request.note());
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasRole('ADMIN')")
    public ProblemView dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody DismissProblemRequest request) {
        return resolutions.dismissByAdmin(id, jwt.getSubject(), request);
    }
}
