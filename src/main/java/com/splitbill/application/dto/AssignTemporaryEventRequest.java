package com.splitbill.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AssignTemporaryEventRequest(
        @NotNull UUID eventId
) {
}
