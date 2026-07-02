package com.splitbill.application.dto;

import java.util.UUID;

public record UserOptionResponse(
        UUID id,
        String nickname,
        boolean active
) {
}
