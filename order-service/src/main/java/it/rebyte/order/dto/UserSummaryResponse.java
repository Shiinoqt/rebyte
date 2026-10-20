package it.rebyte.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Only the fields order-service uses from user-service's {@code UserSummary}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserSummaryResponse(String sub, String username) {
}
