package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CloseEventRequest;
import com.splitbill.application.dto.CreateEventRequest;
import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.usecase.EventUseCase;
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
import java.util.UUID;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventUseCase events;

    public EventController(EventUseCase events) {
        this.events = events;
    }

    @GetMapping
    public List<EventResponse> list(
            @RequestParam(required = false) UUID viewerUserId,
            @RequestParam(required = false) UUID groupId
    ) {
        return events.list(viewerUserId, groupId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse create(@Valid @RequestBody CreateEventRequest request) {
        return events.create(request);
    }

    @PutMapping("/{id}/close")
    public EventResponse close(@PathVariable UUID id, @RequestBody(required = false) CloseEventRequest request) {
        return events.close(id, request);
    }

    @PutMapping("/{id}/reopen")
    public EventResponse reopen(@PathVariable UUID id) {
        return events.reopen(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id, @RequestParam UUID adminUserId) {
        events.delete(id, adminUserId);
    }
}
