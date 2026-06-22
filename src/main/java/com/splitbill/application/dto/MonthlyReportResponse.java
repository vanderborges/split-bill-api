package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MonthlyReportResponse(
        UUID monthId,
        UUID eventId,
        String eventName,
        UUID groupId,
        String groupName,
        int month,
        int year,
        String status,
        BigDecimal totalExpenses,
        List<MonthlyBalanceResponse> balances
) {
}
