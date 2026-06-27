package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record DashboardGroupBalanceResponse(
        UUID groupId,
        String groupName,
        BigDecimal balance
) {
}
