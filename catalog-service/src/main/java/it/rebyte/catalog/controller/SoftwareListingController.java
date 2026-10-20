package it.rebyte.catalog.controller;

import java.util.List;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import it.rebyte.catalog.domain.Listing;
import it.rebyte.catalog.dto.ArtifactUploadPolicyResponse;
import it.rebyte.catalog.dto.ConfirmArtifactRequest;
import it.rebyte.catalog.dto.ArtifactDownloadResponse;
import it.rebyte.catalog.dto.ListingCreatedResponse;
import it.rebyte.catalog.dto.PublishSoftwareRequest;
import it.rebyte.catalog.dto.ReleaseVersionRequest;
import it.rebyte.catalog.dto.VersionResponse;
import it.rebyte.catalog.service.SoftwareArtifactService;
import it.rebyte.catalog.service.ArtifactConfirmService;
import it.rebyte.catalog.service.ArtifactDownloadService;
import it.rebyte.catalog.service.SoftwarePublishService;
import it.rebyte.catalog.service.VersionReleaseService;

@RestController
@RequestMapping("/listings")
public class SoftwareListingController {
    private final SoftwarePublishService publishing;
    private final VersionReleaseService releases;
    private final SoftwareArtifactService artifacts;
    private final ArtifactConfirmService artifactConfirmation;
    private final ArtifactDownloadService artifactDownloads;

    public SoftwareListingController(SoftwarePublishService publishing, VersionReleaseService releases,
            SoftwareArtifactService artifacts, ArtifactConfirmService artifactConfirmation,
            ArtifactDownloadService artifactDownloads) {
        this.publishing = publishing; this.releases = releases; this.artifacts = artifacts;
        this.artifactConfirmation = artifactConfirmation;
        this.artifactDownloads = artifactDownloads;
    }

    @PostMapping("/software")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingCreatedResponse publish(@Valid @RequestBody PublishSoftwareRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ListingCreatedResponse.from(publishing.publishSoftware(request, jwt.getSubject()));
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@listings.isOwner(#id, authentication.name)")
    public VersionResponse release(@PathVariable String id, @Valid @RequestBody ReleaseVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Listing listing = releases.release(id, request, jwt.getSubject());
        var versions = listing.getSoftware().getVersions();
        return VersionResponse.from(versions.get(versions.size() - 1), true);
    }

    @GetMapping("/{id}/versions")
    public List<VersionResponse> versions(@PathVariable String id) {
        Listing listing = releases.get(id);
        String current = listing.getSoftware().currentVersion().getNumber();
        return listing.getSoftware().getVersions().stream().map(v -> VersionResponse.from(v, current.equals(v.getNumber()))).toList();
    }

    @PostMapping("/{id}/versions/{number}/artifact-upload-policy")
    @PreAuthorize("@listings.isOwner(#id, authentication.name)")
    public ArtifactUploadPolicyResponse artifactPolicy(@PathVariable String id, @PathVariable String number) {
        return artifacts.createPolicy(id, number);
    }

    @PostMapping("/{id}/versions/{number}/artifact/confirm")
    @PreAuthorize("@listings.isOwner(#id, authentication.name)")
    public VersionResponse confirmArtifact(@PathVariable String id, @PathVariable String number,
            @Valid @RequestBody ConfirmArtifactRequest request, @AuthenticationPrincipal Jwt jwt) {
        return artifactConfirmation.confirm(id, number, jwt.getSubject(), request.key());
    }

    @GetMapping("/{id}/versions/{number}/download")
    public ArtifactDownloadResponse download(@PathVariable String id, @PathVariable String number,
            @AuthenticationPrincipal Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList("cognito:groups");
        boolean admin = groups != null && groups.contains("ADMIN");
        return artifactDownloads.download(id, number, jwt.getSubject(), admin);
    }
}
