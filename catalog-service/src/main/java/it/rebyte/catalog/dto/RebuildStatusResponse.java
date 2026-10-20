package it.rebyte.catalog.dto;

import java.time.Instant;

public record RebuildStatusResponse(
        boolean running,
        Instant startedAt,
        Instant completedAt,
        String error) {
}
