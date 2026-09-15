package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.GroupInvitePreviewResponse;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.usecase.GroupInviteUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/invites")
public class GroupInviteJoinController {

    private final GroupInviteUseCase invites;
    private final CurrentUser currentUser;

    public GroupInviteJoinController(GroupInviteUseCase invites, CurrentUser currentUser) {
        this.invites = invites;
        this.currentUser = currentUser;
    }

    @GetMapping("/{inviteId}")
    public GroupInvitePreviewResponse preview(@PathVariable UUID inviteId) {
        return invites.preview(inviteId);
    }

    @PostMapping("/{inviteId}/join")
    public GroupMemberResponse join(@PathVariable UUID inviteId) {
        return invites.join(inviteId, currentUser.id());
    }
}
