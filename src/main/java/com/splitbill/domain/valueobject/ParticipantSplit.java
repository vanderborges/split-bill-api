package com.splitbill.domain.valueobject;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Como um participante entra na divisao: por cotas ({@code shareCount}) ou,
 * na divisao por valor, com um valor fixo ({@code fixedAmount}) - quando
 * usado, vale para todos os participantes da despesa.
 */
public record ParticipantSplit(UUID userId, int shareCount, String shareDescription, BigDecimal fixedAmount) {

    public ParticipantSplit(UUID userId, int shareCount, String shareDescription) {
        this(userId, shareCount, shareDescription, null);
    }
}
