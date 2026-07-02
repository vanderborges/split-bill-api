package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateEventRequest(
        @NotBlank String name,
        String description,
        @NotNull EventType type,
        UUID monthId,
        Integer month,
        Integer year,
        @NotNull UUID groupId
) {
}
