package com.splitbill.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record ExpensePayerRequest(
        @NotNull UUID userId,
        @NotNull @Positive BigDecimal amount
) {
}
