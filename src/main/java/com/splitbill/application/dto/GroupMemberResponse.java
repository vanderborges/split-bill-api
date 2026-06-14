package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.GroupMemberRole;

import java.util.UUID;

public record GroupMemberResponse(
        UUID id,
        UUID groupId,
        UUID userId,
        String nickname,
        GroupMemberRole role,
        boolean active
) {
}
