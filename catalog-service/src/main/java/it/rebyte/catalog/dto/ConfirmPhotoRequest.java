package it.rebyte.catalog.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPhotoRequest(@NotBlank String key) {
}
