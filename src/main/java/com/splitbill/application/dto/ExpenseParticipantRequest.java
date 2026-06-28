package com.splitbill.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ExpenseParticipantRequest(
        @NotNull UUID userId,
        @Positive Integer shareCount,
        String shareDescription
) {
}
