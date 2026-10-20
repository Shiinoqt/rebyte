package it.rebyte.catalog.domain;

import java.time.Instant;

public class Version {
    private final String number;
    private final Instant release;
    private final String notes;
    private final String artifactKey;

    public Version(String number, Instant release, String notes, String artifactKey) {
        this.number = number;
        this.release = release;
        this.notes = notes;
        this.artifactKey = artifactKey;
    }

    public String getNumber() { return number; }
    public Instant getRelease() { return release; }
    public String getNotes() { return notes; }
    public String getArtifactKey() { return artifactKey; }
}
