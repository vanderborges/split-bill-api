package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;

import java.time.LocalDateTime;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        String description,
        EventType type,
        EventStatus status,
        UUID groupId,
        UUID monthId,
        Integer month,
        Integer year,
        LocalDateTime createdAt,
        LocalDateTime closedAt
) {
}
