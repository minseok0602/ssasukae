package com.ssafy.ssasukae.domain.performance.websocket.request;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.*;

public record PerformanceConnectionRecoveryRequest(
    @NotBlank @Size(max = 100) String interruptionId,
    @NotNull OffsetDateTime interruptedAt,
    @NotNull @PositiveOrZero Long playbackPositionMs) {}
