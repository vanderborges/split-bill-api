package com.splitbill.application.dto;

import java.util.UUID;

/**
 * {@code receiverUserId}: quem vai receber os pagamentos deste evento (todo
 * devedor paga essa pessoa). Nulo = sugerir pagamentos entre devedores e
 * credores. {@code receiverChosen} so vem do app novo (que mostra a
 * escolha no "Fechar evento"): sem ele (app antigo) vale o recebedor do
 * grupo, como era antes.
 */
public record StartSettlementRequest(
        UUID consolidateToEventId,
        UUID receiverUserId,
        Boolean receiverChosen
) {
}
