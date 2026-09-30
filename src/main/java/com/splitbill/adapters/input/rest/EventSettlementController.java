package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.UpdateSettlementStatusRequest;
import com.splitbill.application.usecase.EventSettlementUseCase;
import com.splitbill.application.usecase.EventUseCase;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events/{eventId}/settlements")
public class EventSettlementController {

    private final EventSettlementUseCase settlements;
    private final EventUseCase events;
    private final CurrentUser currentUser;

    public EventSettlementController(
            EventSettlementUseCase settlements,
            EventUseCase events,
            CurrentUser currentUser
    ) {
        this.settlements = settlements;
        this.events = events;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<EventSettlementResponse> listByEvent(@PathVariable UUID eventId) {
        return settlements.listByEvent(eventId, currentUser.id());
    }

    @PutMapping("/{settlementId}")
    public EventSettlementResponse updateStatus(
            @PathVariable UUID eventId,
            @PathVariable UUID settlementId,
            @Valid @RequestBody UpdateSettlementStatusRequest request
    ) {
        EventSettlementResponse response = settlements.updateStatus(settlementId, request, currentUser.id());
        closeIfFullySettled(eventId);
        return response;
    }

    /**
     * Depois de marcar um pagamento, tenta fechar o evento sozinho -
     * reaproveita a validação de {@link EventUseCase#close}, que só fecha
     * de fato quando o evento está em SETTLING e não há mais nenhum
     * pagamento pendente. Se ainda não for o caso (ou o evento não estiver
     * em SETTLING), a exceção é esperada e é só ignorada - fechar
     * automaticamente é um efeito colateral, não deve quebrar a resposta
     * de quem só queria marcar um pagamento como pago.
     */
    private void closeIfFullySettled(UUID eventId) {
        try {
            events.close(eventId, currentUser.id());
        } catch (DomainException ignored) {
            // Ainda falta alguem pagar, ou o evento nao esta em SETTLING - normal.
        }
    }
}
