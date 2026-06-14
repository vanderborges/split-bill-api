package com.splitbill.application.dto;

import java.util.UUID;

public record GroupResponse(
        UUID id,
        String name,
        String description,
        UUID createdByUserId,
        boolean active
) {
}
