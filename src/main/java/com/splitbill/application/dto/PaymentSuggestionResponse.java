package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentSuggestionResponse(
        UUID fromUserId,
        String fromNickname,
        UUID toUserId,
        String toNickname,
        BigDecimal amount
) {
}
