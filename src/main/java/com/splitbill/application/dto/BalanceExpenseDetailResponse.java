package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record BalanceExpenseDetailResponse(
        UUID expenseId,
        String description,
        LocalDate expenseDate,
        String category,
        BigDecimal amount,
        BigDecimal consumed,
        BigDecimal paid,
        BigDecimal impact
) {
}
