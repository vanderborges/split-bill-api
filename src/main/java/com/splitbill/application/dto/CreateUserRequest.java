package com.splitbill.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
        @NotBlank String fullName,
        @NotBlank String nickname,
        @Email @NotBlank String email,
        @NotBlank String phone,
        @NotBlank String pixKey,
        boolean admin
) {
}
