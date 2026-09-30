package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CreateEventRequest;
import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.dto.SetEventReceiverRequest;
import com.splitbill.application.dto.StartSettlementRequest;
import com.splitbill.application.usecase.EventUseCase;
import com.splitbill.application.usecase.NotificationUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventUseCase events;
    private final NotificationUseCase notifications;
    private final CurrentUser currentUser;

    public EventController(
            EventUseCase events,
            NotificationUseCase notifications,
            CurrentUser currentUser
    ) {
        this.events = events;
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<EventResponse> list(
            @RequestParam(required = false) UUID groupId
    ) {
        return events.list(currentUser.id(), groupId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody CreateEventRequest request) {
        return events.create(request, currentUser.id());
    }

    @PutMapping("/{id}/start-settlement")
    public EventResponse startSettlement(
            @PathVariable UUID id,
            @RequestBody(required = false) StartSettlementRequest request
    ) {
        return events.startSettlement(id, request, currentUser.id());
    }

    @PutMapping("/{id}/close")
    public EventResponse close(@PathVariable UUID id) {
        return events.close(id, currentUser.id());
    }

    @PutMapping("/{id}/reopen")
    public EventResponse reopen(@PathVariable UUID id) {
        return events.reopen(id, currentUser.id());
    }

    @PostMapping("/{id}/billing-alert")
    public Map<String, Integer> sendBillingAlert(@PathVariable UUID id) {
        return Map.of("recipients", notifications.sendBillingAlert(id, currentUser.id()));
    }

    @PutMapping("/{id}/receiver")
    public EventResponse setReceiver(
            @PathVariable UUID id,
            @RequestBody(required = false) SetEventReceiverRequest request
    ) {
        UUID receiverUserId = request == null ? null : request.userId();
        return events.setReceiver(id, receiverUserId, currentUser.id());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        events.delete(id, currentUser.id());
    }
}
