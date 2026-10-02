package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DashboardEventBalanceResponse(
        UUID eventId,
        String eventName,
        String eventStatus,
        BigDecimal balance
) {
}
