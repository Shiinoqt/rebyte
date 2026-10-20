package it.rebyte.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DismissProblemRequest(@NotBlank @Size(max = 1000)
        @Pattern(regexp = "[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*") String note) {

    public DismissProblemRequest {
        if (note != null) {
            note = note.trim();
        }
    }
}
