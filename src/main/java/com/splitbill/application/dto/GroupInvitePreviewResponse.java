package com.splitbill.application.dto;

import java.util.UUID;

public record GroupInvitePreviewResponse(
        UUID id,
        UUID groupId,
        String groupName,
        boolean active,
        UUID eventId,
        String eventName
) {
}
