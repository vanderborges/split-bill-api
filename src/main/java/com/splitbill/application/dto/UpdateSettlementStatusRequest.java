package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.SettlementStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateSettlementStatusRequest(
        @NotNull SettlementStatus status
) {
}
