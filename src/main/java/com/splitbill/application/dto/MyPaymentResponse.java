package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.SettlementStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Botao "Pagar" do Relatorio: o acerto de quem esta logado no evento, com o
 * mesmo texto da notificacao de cobranca e a chave PIX (quando ha um unico
 * destinatario).
 */
public record MyPaymentResponse(
        UUID settlementId,
        SettlementStatus status,
        BigDecimal amount,
        String message,
        String pixKey,
        String receiverName
) {
}
