package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.AddGroupMemberRequest;
import com.splitbill.application.dto.AssignTemporaryEventRequest;
import com.splitbill.application.dto.CreateGroupRequest;
import com.splitbill.application.dto.GroupInviteResponse;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.dto.GroupResponse;
import com.splitbill.application.dto.SetGroupAutoSettlementDayRequest;
import com.splitbill.application.dto.SetGroupReceiverRequest;
import com.splitbill.application.dto.UpdateGroupRequest;
import com.splitbill.application.usecase.GroupInviteUseCase;
import com.splitbill.application.usecase.GroupUseCase;
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
import java.util.UUID;

@RestController
@RequestMapping("/groups")
public class GroupController {

    private final GroupUseCase groups;
    private final GroupInviteUseCase invites;
    private final CurrentUser currentUser;

    public GroupController(GroupUseCase groups, GroupInviteUseCase invites, CurrentUser currentUser) {
        this.groups = groups;
        this.invites = invites;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<GroupResponse> list() {
        return groups.listByUser(currentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@Valid @RequestBody CreateGroupRequest request) {
        return groups.create(request, currentUser.id());
    }

    @PutMapping("/{groupId}")
    public GroupResponse update(
            @PathVariable UUID groupId,
            @Valid @RequestBody UpdateGroupRequest request
    ) {
        return groups.update(groupId, request, currentUser.id());
    }

    @PutMapping("/{groupId}/receiver")
    public GroupResponse setReceiver(
            @PathVariable UUID groupId,
            @RequestBody(required = false) SetGroupReceiverRequest request
    ) {
        UUID receiverUserId = request == null ? null : request.userId();
        return groups.setReceiver(groupId, receiverUserId, currentUser.id());
    }

    @PutMapping("/{groupId}/auto-settlement-day")
    public GroupResponse setAutoSettlementDay(
            @PathVariable UUID groupId,
            @RequestBody(required = false) SetGroupAutoSettlementDayRequest request
    ) {
        Integer day = request == null ? null : request.day();
        return groups.setAutoSettlementDay(groupId, day, currentUser.id());
    }

    @GetMapping("/{groupId}/members")
    public List<GroupMemberResponse> listMembers(
            @PathVariable UUID groupId,
            @RequestParam(required = false) UUID eventId
    ) {
        return groups.listMembers(groupId, currentUser.id(), eventId);
    }

    @GetMapping("/{groupId}/members/temporary")
    public List<GroupMemberResponse> listTemporaryMembers(@PathVariable UUID groupId) {
        return groups.listTemporaryMembers(groupId, currentUser.id());
    }

    @PutMapping("/{groupId}/members/{userId}/temporary-event")
    public GroupMemberResponse assignTemporaryEvent(
            @PathVariable UUID groupId,
            @PathVariable UUID userId,
            @Valid @RequestBody AssignTemporaryEventRequest request
    ) {
        return groups.assignTemporaryEvent(groupId, userId, request.eventId(), currentUser.id());
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupMemberResponse addMember(
            @PathVariable UUID groupId,
            @Valid @RequestBody AddGroupMemberRequest request
    ) {
        return groups.addMember(groupId, request, currentUser.id());
    }

    @DeleteMapping("/{groupId}/members/{userId}")
    public void removeMember(@PathVariable UUID groupId, @PathVariable UUID userId) {
        groups.removeMember(groupId, userId, currentUser.id());
    }

    @PostMapping("/{groupId}/leave")
    public void leave(@PathVariable UUID groupId) {
        groups.leave(groupId, currentUser.id());
    }

    @DeleteMapping("/{groupId}")
    public void delete(@PathVariable UUID groupId) {
        groups.delete(groupId, currentUser.id());
    }

    @PostMapping("/{groupId}/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupInviteResponse getOrCreateInvite(@PathVariable UUID groupId) {
        return invites.getOrCreate(groupId, currentUser.id());
    }

    // Convite temporario: quem entrar por ele so participa deste evento.
    @PostMapping("/{groupId}/events/{eventId}/temporary-invite")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupInviteResponse getOrCreateTemporaryInvite(@PathVariable UUID groupId, @PathVariable UUID eventId) {
        return invites.getOrCreateTemporary(groupId, eventId, currentUser.id());
    }

    @PostMapping("/{groupId}/invite/regenerate")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupInviteResponse regenerateInvite(@PathVariable UUID groupId) {
        return invites.regenerate(groupId, currentUser.id());
    }
}
