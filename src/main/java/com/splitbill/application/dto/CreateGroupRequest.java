package com.splitbill.application.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateGroupRequest(
        @NotBlank String name,
        String description
) {
}
