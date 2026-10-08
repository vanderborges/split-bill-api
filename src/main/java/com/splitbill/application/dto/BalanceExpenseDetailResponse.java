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
        BigDecimal impact,
        Integer installmentNumber,
        Integer totalInstallments,
        boolean subscription,
        boolean subscriptionCancelled,
        // Cotas de quem esta sendo detalhado (soma, se for mais de um userId),
        // o total de cotas da despesa e o motivo informado no cadastro.
        int shareCount,
        int totalShares,
        String shareDescription
) {
}
