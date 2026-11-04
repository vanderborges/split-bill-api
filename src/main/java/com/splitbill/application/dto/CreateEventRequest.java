package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.EventType;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateEventRequest(
        String name,
        String description,
        @NotNull EventType type,
        UUID monthId,
        Integer month,
        Integer year,
        @NotNull UUID groupId,
        // Series do mes anterior (ja fora de OPEN) que seguem no mes novo.
        // App antigo nao manda: nenhuma segue, como antes.
        List<UUID> continueInstallmentGroupIds
) {
}
