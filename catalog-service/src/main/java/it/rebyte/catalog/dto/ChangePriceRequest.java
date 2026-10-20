package it.rebyte.catalog.dto;
import java.math.BigDecimal;
import jakarta.validation.constraints.NotNull;
public record ChangePriceRequest(@NotNull BigDecimal price) {}
