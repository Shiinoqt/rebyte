package it.rebyte.catalog.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmArtifactRequest(@NotBlank String key) {}
