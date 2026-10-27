package com.splitbill.application.dto;

import com.splitbill.domain.valueobject.SettlementStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Botao "Pagar" do Relatorio: o acerto de quem esta logado no evento, com o
 * mesmo texto da notificacao de cobranca e a chave PIX (quando ha um unico
 * destinatario). {@code headline} e {@code payees} montam o pop-up linha a
 * linha: o valor devido e, para cada pessoa a pagar, o valor e o PIX.
 */
public record MyPaymentResponse(
        UUID settlementId,
        SettlementStatus status,
        BigDecimal amount,
        String message,
        String pixKey,
        String receiverName,
        String headline,
        List<Payee> payees
) {
    public record Payee(String name, BigDecimal amount, String pixKey) {
    }
}
