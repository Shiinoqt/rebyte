package it.rebyte.catalog.dto;

import java.util.List;
import java.util.Set;

import it.rebyte.catalog.domain.Platform;

/** Public software-specific content, deliberately detached from the embedded domain part. */
public record SoftwareListingView(Set<Platform> platforms, List<VersionResponse> versions) {
    public SoftwareListingView {
        platforms = Set.copyOf(platforms);
        versions = List.copyOf(versions);
    }
}
