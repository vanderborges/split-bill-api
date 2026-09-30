package com.splitbill.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID eventId,
        String eventName,
        String message,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {
}
