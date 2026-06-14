package com.splitbill.domain.valueobject;

import java.math.BigDecimal;
import java.util.UUID;

public record ParticipantBalance(
        UUID userId,
        BigDecimal totalConsumed,
        BigDecimal totalPaid,
        BigDecimal balance
) {
}
