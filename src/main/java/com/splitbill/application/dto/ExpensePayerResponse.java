package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ExpensePayerResponse(UUID userId, String nickname, BigDecimal amount) {
}
