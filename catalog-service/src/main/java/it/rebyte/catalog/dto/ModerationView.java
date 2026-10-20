package it.rebyte.catalog.dto;

import java.time.Instant;

public record ModerationView(String reason, Instant instant) { }
