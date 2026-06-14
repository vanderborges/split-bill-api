package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.GroupMemberRole;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddGroupMemberRequest(
        @NotNull UUID adminUserId,
        @NotNull UUID userId,
        @NotNull GroupMemberRole role
) {
}
