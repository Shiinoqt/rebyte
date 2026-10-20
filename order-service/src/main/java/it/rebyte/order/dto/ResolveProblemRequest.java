package it.rebyte.order.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResolveProblemRequest(@Size(max = 1000)
        @Pattern(regexp = "[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*") String note) {

    public ResolveProblemRequest {
        if (note != null) {
            note = note.trim();
            if (note.isEmpty()) {
                note = null;
            }
        }
    }
}
