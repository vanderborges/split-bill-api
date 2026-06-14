package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.SettlementStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateSettlementStatusRequest(
        @NotNull SettlementStatus status,
        UUID adminUserId
) {
}
