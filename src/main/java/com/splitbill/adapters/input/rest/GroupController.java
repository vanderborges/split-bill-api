package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.AddGroupMemberRequest;
import com.splitbill.application.dto.CreateGroupRequest;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.dto.GroupResponse;
import com.splitbill.application.usecase.GroupUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final CurrentUser currentUser;

    public GroupController(GroupUseCase groups, CurrentUser currentUser) {
        this.groups = groups;
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

    @GetMapping("/{groupId}/members")
    public List<GroupMemberResponse> listMembers(
            @PathVariable UUID groupId
    ) {
        return groups.listMembers(groupId, currentUser.id());
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupMemberResponse addMember(
            @PathVariable UUID groupId,
            @Valid @RequestBody AddGroupMemberRequest request
    ) {
        return groups.addMember(groupId, request, currentUser.id());
    }

    @DeleteMapping("/{groupId}")
    public void delete(@PathVariable UUID groupId) {
        groups.delete(groupId, currentUser.id());
    }
}
