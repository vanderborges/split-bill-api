package com.splitbill.application.dto;

public record AuthResponse(
        String token,
        UserResponse user
) {
}
