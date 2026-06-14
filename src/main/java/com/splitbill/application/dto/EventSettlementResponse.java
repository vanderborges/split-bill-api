package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.SettlementRole;
import com.splitbill.domain.valueobject.SettlementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record EventSettlementResponse(
        UUID id,
        UUID eventId,
        UUID userId,
        String nickname,
        SettlementRole role,
        BigDecimal amount,
        SettlementStatus status,
        UUID updatedByAdminId,
        LocalDateTime updatedAt
) {
}
