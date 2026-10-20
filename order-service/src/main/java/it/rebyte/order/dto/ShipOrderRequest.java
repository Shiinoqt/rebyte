package it.rebyte.order.dto;

import it.rebyte.order.domain.Carrier;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ShipOrderRequest(@NotNull Carrier carrier,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9 ./-]*") String trackingCode) {
    public ShipOrderRequest {
        if (trackingCode != null) trackingCode = trackingCode.replaceAll("^ +| +$", "");
    }
}
