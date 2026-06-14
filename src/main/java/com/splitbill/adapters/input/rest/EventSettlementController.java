package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.UpdateSettlementStatusRequest;
import com.splitbill.application.usecase.EventSettlementUseCase;
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

    public EventSettlementController(EventSettlementUseCase settlements) {
        this.settlements = settlements;
    }

    @GetMapping
    public List<EventSettlementResponse> listByEvent(@PathVariable UUID eventId) {
        return settlements.listByEvent(eventId);
    }

    @PutMapping("/{settlementId}")
    public EventSettlementResponse updateStatus(
            @PathVariable UUID settlementId,
            @Valid @RequestBody UpdateSettlementStatusRequest request
    ) {
        return settlements.updateStatus(settlementId, request);
    }
}
