package it.rebyte.messaging.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/** Text is validated by MessageTextRules so that every invalid form answers invalid-message-text. */
public record SendMessageRequest(String text, @Size(max = 3) List<String> imageKeys) {
}
