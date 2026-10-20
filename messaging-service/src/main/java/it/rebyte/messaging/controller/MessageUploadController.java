package it.rebyte.messaging.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.messaging.dto.ChatImageUploadPolicyRequest;
import it.rebyte.messaging.dto.ChatImageUploadPolicyResponse;
import it.rebyte.messaging.service.ChatImageService;
import jakarta.validation.Valid;

@RestController
public class MessageUploadController {

    private final ChatImageService images;

    public MessageUploadController(ChatImageService images) {
        this.images = images;
    }

    @PostMapping("/messages/image-upload-policy")
    public ChatImageUploadPolicyResponse uploadPolicy(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChatImageUploadPolicyRequest request) {
        return images.createUploadPolicy(jwt.getSubject(), request.contentType());
    }
}
