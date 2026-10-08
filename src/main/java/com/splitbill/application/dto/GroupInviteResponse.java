package com.splitbill.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record GroupInviteResponse(
        UUID id,
        UUID groupId,
        UUID createdByUserId,
        boolean active,
        LocalDateTime createdAt,
        // Convite temporario: evento ao qual a pessoa fica amarrada (nulo = convite do grupo).
        UUID eventId
) {
}
