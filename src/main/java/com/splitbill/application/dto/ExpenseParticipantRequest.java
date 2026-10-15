package com.splitbill.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record ExpenseParticipantRequest(
        @NotNull UUID userId,
        @Positive Integer shareCount,
        String shareDescription,
        // Divisao por valor: quando informado (para TODOS os participantes),
        // e o valor exato de cada um, em vez de dividir por cotas.
        BigDecimal shareAmount
) {
}
