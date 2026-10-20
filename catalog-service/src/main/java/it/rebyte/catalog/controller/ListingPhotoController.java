package it.rebyte.catalog.controller;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import it.rebyte.catalog.dto.ConfirmPhotoRequest;
import it.rebyte.catalog.dto.PhotoUploadPolicyResponse;
import it.rebyte.catalog.service.ListingPhotoService;

@RestController
@RequestMapping("/listings/{id}/photos")
public class ListingPhotoController {
    private final ListingPhotoService service;

    public ListingPhotoController(ListingPhotoService service) {
        this.service = service;
    }

    @PostMapping("/upload-policy")
    @PreAuthorize("@listings.isOwner(#id, authentication.name)")
    public PhotoUploadPolicyResponse uploadPolicy(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.createUploadPolicy(id, jwt.getSubject());
    }

    @PostMapping("/confirm")
    @PreAuthorize("@listings.isOwner(#id, authentication.name)")
    public List<String> confirm(@PathVariable String id, @Valid @RequestBody ConfirmPhotoRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.confirmUpload(id, jwt.getSubject(), request.key());
    }
}
