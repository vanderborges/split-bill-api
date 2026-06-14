package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.MonthStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record MonthResponse(
        UUID id,
        UUID eventId,
        int month,
        int year,
        MonthStatus status,
        LocalDateTime openedAt,
        LocalDateTime closedAt
) {
}
