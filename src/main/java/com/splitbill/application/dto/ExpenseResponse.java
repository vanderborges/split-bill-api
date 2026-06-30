package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExpenseResponse(
        UUID id,
        String description,
        BigDecimal amount,
        LocalDate expenseDate,
        String category,
        UUID payerId,
        String payerNickname,
        UUID createdByUserId,
        UUID monthId,
        UUID eventId,
        UUID sourceEventId,
        UUID installmentGroupId,
        Integer installmentNumber,
        Integer totalInstallments,
        List<ExpensePayerResponse> payers,
        List<ExpenseParticipantResponse> participants
) {
}
