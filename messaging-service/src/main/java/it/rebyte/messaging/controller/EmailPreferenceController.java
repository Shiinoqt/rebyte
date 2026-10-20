package it.rebyte.messaging.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.messaging.dto.EmailPreferenceView;
import it.rebyte.messaging.dto.UpdateEmailPreferenceRequest;
import it.rebyte.messaging.service.EmailPreferenceService;
import jakarta.validation.Valid;

/** The caller only ever reads and writes their own preference: the sub comes from the JWT, never from the request. */
@RestController
@RequestMapping("/notifications/email-preferences")
public class EmailPreferenceController {

    private final EmailPreferenceService service;

    public EmailPreferenceController(EmailPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public EmailPreferenceView get(@AuthenticationPrincipal Jwt jwt) {
        return new EmailPreferenceView(service.isEnabled(jwt.getSubject()));
    }

    @PutMapping
    public EmailPreferenceView update(
            @Valid @RequestBody UpdateEmailPreferenceRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.setEnabled(jwt.getSubject(), request.emailEnabled());
    }
}
