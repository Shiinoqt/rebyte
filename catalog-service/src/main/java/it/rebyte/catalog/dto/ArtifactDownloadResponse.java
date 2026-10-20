package it.rebyte.catalog.dto;

import java.time.Instant;

public record ArtifactDownloadResponse(String url, Instant expiresAt) {}
