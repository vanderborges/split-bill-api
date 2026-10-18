package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.CreatePlaceholderRequest;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.dto.RenamePlaceholderRequest;
import com.splitbill.application.usecase.PlaceholderUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Pessoas sem cadastro do grupo (so admin) - ver {@link PlaceholderUseCase}. */
@RestController
@RequestMapping("/groups/{groupId}/placeholders")
public class PlaceholderController {

    private final PlaceholderUseCase placeholders;
    private final CurrentUser currentUser;

    public PlaceholderController(PlaceholderUseCase placeholders, CurrentUser currentUser) {
        this.placeholders = placeholders;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<GroupMemberResponse> list(@PathVariable UUID groupId) {
        return placeholders.list(groupId, currentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupMemberResponse create(
            @PathVariable UUID groupId,
            @Valid @RequestBody CreatePlaceholderRequest request
    ) {
        return placeholders.create(groupId, request, currentUser.id());
    }

    @PutMapping("/{userId}")
    public GroupMemberResponse rename(
            @PathVariable UUID groupId,
            @PathVariable UUID userId,
            @Valid @RequestBody RenamePlaceholderRequest request
    ) {
        return placeholders.rename(groupId, userId, request.nickname(), currentUser.id());
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID groupId, @PathVariable UUID userId) {
        placeholders.delete(groupId, userId, currentUser.id());
    }
}
