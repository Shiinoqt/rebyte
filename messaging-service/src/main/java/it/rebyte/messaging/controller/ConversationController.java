package it.rebyte.messaging.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.messaging.dto.ConversationPage;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.MessagePage;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.dto.SendMessageRequest;
import it.rebyte.messaging.dto.StartConversationRequest;
import it.rebyte.messaging.service.ConversationQueryService;
import it.rebyte.messaging.service.ConversationService;
import it.rebyte.messaging.service.MessageService;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationService conversations;
    private final MessageService messages;
    private final ConversationQueryService queries;

    public ConversationController(ConversationService conversations, MessageService messages,
            ConversationQueryService queries) {
        this.conversations = conversations;
        this.messages = messages;
        this.queries = queries;
    }

    @GetMapping
    public ConversationPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return queries.list(jwt.getSubject(), cursor, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartConversationRequest request) {
        return conversations.start(jwt.getSubject(), request);
    }

    @GetMapping("/{id}")
    public ConversationView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return conversations.get(jwt.getSubject(), id);
    }

    /** Messages are immutable (D-34): no update or delete mapping exists. */
    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody SendMessageRequest request) {
        return messages.send(jwt.getSubject(), id, request);
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<MessagePage> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.messages(jwt.getSubject(), id, cursor, size));
    }
}
