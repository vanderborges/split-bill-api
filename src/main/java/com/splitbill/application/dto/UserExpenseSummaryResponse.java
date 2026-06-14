package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UserExpenseSummaryResponse(
        UUID userId,
        String nickname,
        LocalDate from,
        LocalDate to,
        BigDecimal totalConsumed,
        BigDecimal totalPaid,
        BigDecimal balance,
        List<ExpenseResponse> expenses
) {
}
