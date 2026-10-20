package it.rebyte.catalog.dto;

import java.time.Instant;
import it.rebyte.catalog.domain.Version;

public record VersionResponse(String number, Instant release, String notes, boolean artifactAvailable, boolean current) {
    public static VersionResponse from(Version version, boolean current) {
        return new VersionResponse(version.getNumber(), version.getRelease(), version.getNotes(),
                version.getArtifactKey() != null, current);
    }
}
