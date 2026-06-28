package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateExpenseRequest(
        @NotBlank String description,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate expenseDate,
        @NotBlank String category,
        UUID payerId,
        UUID monthId,
        UUID eventId,
        List<UUID> participantIds,
        List<ExpenseParticipantRequest> participants,
        List<ExpensePayerRequest> payers,
        Integer installments
) {
}
