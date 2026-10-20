package it.rebyte.messaging.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatImageUploadPolicyRequest(@NotBlank String contentType) {
}
