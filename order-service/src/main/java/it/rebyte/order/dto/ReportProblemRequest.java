package it.rebyte.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import it.rebyte.order.domain.ProblemReason;

public record ReportProblemRequest(@NotNull ProblemReason reason,
        @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*") String description) {

    public ReportProblemRequest {
        if (description != null) {
            description = description.trim();
        }
    }
}
