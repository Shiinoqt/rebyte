package it.rebyte.catalog.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseVersionRequest(@NotBlank String number, String notes) {
}
