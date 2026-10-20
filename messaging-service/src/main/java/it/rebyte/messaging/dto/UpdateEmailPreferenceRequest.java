package it.rebyte.messaging.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateEmailPreferenceRequest(@NotNull Boolean emailEnabled) {
}
