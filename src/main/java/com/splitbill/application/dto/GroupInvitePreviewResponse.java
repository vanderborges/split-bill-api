package com.splitbill.application.dto;

import java.util.List;
import java.util.UUID;

public record GroupInvitePreviewResponse(
        UUID id,
        UUID groupId,
        String groupName,
        boolean active,
        UUID eventId,
        String eventName,
        // Nomes cadastrados pelo admin, ainda sem dono, que quem entra por
        // este convite pode escolher ("Quem e voce?").
        List<PlaceholderOption> placeholders
) {
    public record PlaceholderOption(UUID userId, String nickname) {
    }
}
