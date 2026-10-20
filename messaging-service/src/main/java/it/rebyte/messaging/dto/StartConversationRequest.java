package it.rebyte.messaging.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Text is validated by MessageTextRules so that every invalid form answers invalid-message-text. */
public record StartConversationRequest(
        @NotBlank @Pattern(regexp = StartConversationRequest.LISTING_ID_PATTERN) String listingId,
        String text, @Size(max = 3) List<String> imageKeys) {

    public static final String LISTING_ID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
}
