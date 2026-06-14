package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateGroupRequest(
        @NotBlank String name,
        String description,
        @NotNull UUID adminUserId
) {
}
