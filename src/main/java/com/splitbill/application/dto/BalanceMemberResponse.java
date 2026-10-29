package com.splitbill.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Uma pessoa dentro da linha de saldo de um titular de cobranca (ele e quem
 * tem cobranca vinculada a ele), com o saldo so dela - o app separa os
 * gastos de cada um no Extrato.
 */
public record BalanceMemberResponse(
        UUID userId,
        String nickname,
        BigDecimal balance,
        // Pagou ou consumiu algo neste evento.
        boolean active
) {
}
