package com.splitbill.application.dto;

import java.util.List;
import java.util.UUID;

public record DashboardGroupBalanceResponse(
        UUID groupId,
        String groupName,
        List<DashboardEventBalanceResponse> events
) {
}
