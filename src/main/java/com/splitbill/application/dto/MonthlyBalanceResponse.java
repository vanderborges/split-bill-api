package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record MonthlyBalanceResponse(
        UUID userId,
        String nickname,
        BigDecimal totalConsumed,
        BigDecimal totalPaid,
        BigDecimal balance
) {
}
