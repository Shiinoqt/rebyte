package it.rebyte.messaging.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.messaging.service.DeadLetterReplayService;

@RestController
public class MessagingDeadLetterController {

    private final DeadLetterReplayService replay;

    public MessagingDeadLetterController(DeadLetterReplayService replay) {
        this.replay = replay;
    }

    @PostMapping("/admin/messaging/dead-letters/{queue}/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> replayOne(@PathVariable String queue, @AuthenticationPrincipal Jwt jwt) {
        var result = replay.replayOne(queue, jwt.getSubject());
        if (!result.replayed()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().header("X-Audit-Id", result.auditId()).build();
    }
}
