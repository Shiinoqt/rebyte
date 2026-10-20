package it.rebyte.messaging.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.messaging.dto.NotificationPage;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.dto.ReadAllResult;
import it.rebyte.messaging.dto.UnreadSummary;
import it.rebyte.messaging.service.NotificationQueryService;
import it.rebyte.messaging.service.NotificationCommandService;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationQueryService queries;
    private final NotificationCommandService commands;

    public NotificationController(NotificationQueryService queries, NotificationCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    public NotificationPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Boolean unread,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return queries.list(jwt.getSubject(), unread, cursor, size);
    }

    @GetMapping("/unread-summary")
    public UnreadSummary summary(@AuthenticationPrincipal Jwt jwt) {
        return queries.summary(jwt.getSubject());
    }

    @PostMapping("/{id}/read")
    public NotificationView markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return commands.markRead(jwt.getSubject(), id);
    }

    @PostMapping("/read-all")
    public ReadAllResult markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return commands.markAllRead(jwt.getSubject());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        commands.delete(jwt.getSubject(), id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRead(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Boolean read) {
        commands.deleteRead(jwt.getSubject(), read);
    }
}
