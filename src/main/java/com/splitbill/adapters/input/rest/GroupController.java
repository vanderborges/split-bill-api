package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.AddGroupMemberRequest;
import com.splitbill.application.dto.CreateGroupRequest;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.dto.GroupResponse;
import com.splitbill.application.usecase.GroupUseCase;
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

    public GroupController(GroupUseCase groups) {
        this.groups = groups;
    }

    @GetMapping
    public List<GroupResponse> list(@RequestParam(required = false) UUID viewerUserId) {
        return groups.listByUser(viewerUserId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@Valid @RequestBody CreateGroupRequest request) {
        return groups.create(request);
    }

    @GetMapping("/{groupId}/members")
    public List<GroupMemberResponse> listMembers(
            @PathVariable UUID groupId,
            @RequestParam(required = false) UUID viewerUserId
    ) {
        return groups.listMembers(groupId, viewerUserId);
    }

    @PostMapping("/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupMemberResponse addMember(
            @PathVariable UUID groupId,
            @Valid @RequestBody AddGroupMemberRequest request
    ) {
        return groups.addMember(groupId, request);
    }

    @DeleteMapping("/{groupId}")
    public void delete(@PathVariable UUID groupId, @RequestParam UUID adminUserId) {
        groups.delete(groupId, adminUserId);
    }
}
