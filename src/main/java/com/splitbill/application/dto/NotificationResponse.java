package com.splitbill.application.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID eventId,
        String eventName,
        String message,
        Instant createdAt,
        Instant readAt
) {
}
