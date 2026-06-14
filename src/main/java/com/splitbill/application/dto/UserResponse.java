package com.splitbill.application.dto;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String fullName,
        String nickname,
        String email,
        String phone,
        String pixKey,
        boolean admin,
        boolean active
) {
}
