package com.splitbill.application.usecase;

import com.splitbill.application.dto.GroupInvitePreviewResponse;
import com.splitbill.application.dto.GroupInviteResponse;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.GroupInviteJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupInviteJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class GroupInviteUseCase {

    private final GroupInviteJpaRepository invites;
    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository members;
    private final UserJpaRepository users;
    private final GroupUseCase groupRules;

    public GroupInviteUseCase(
            GroupInviteJpaRepository invites,
            GroupJpaRepository groups,
            GroupMemberJpaRepository members,
            UserJpaRepository users,
            GroupUseCase groupRules
    ) {
        this.invites = invites;
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.groupRules = groupRules;
    }

    @Transactional
    public GroupInviteResponse getOrCreate(UUID groupId, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupJpaEntity group = activeGroup(groupId);
        GroupInviteJpaEntity invite = invites.findFirstByGroupIdAndActiveTrueOrderByCreatedAtDesc(groupId)
                .orElseGet(() -> createInvite(group, requesterId));
        return toResponse(invite);
    }

    @Transactional
    public GroupInviteResponse regenerate(UUID groupId, UUID requesterId) {
        groupRules.requireAdmin(groupId, requesterId);
        GroupJpaEntity group = activeGroup(groupId);
        invites.findFirstByGroupIdAndActiveTrueOrderByCreatedAtDesc(groupId)
                .ifPresent(current -> current.setActive(false));
        return toResponse(createInvite(group, requesterId));
    }

    @Transactional(readOnly = true)
    public GroupInvitePreviewResponse preview(UUID inviteId) {
        GroupInviteJpaEntity invite = activeInvite(inviteId);
        return new GroupInvitePreviewResponse(
                invite.getId(),
                invite.getGroup().getId(),
                invite.getGroup().getName(),
                invite.isActive()
        );
    }

    @Transactional
    public GroupMemberResponse join(UUID inviteId, UUID requesterId) {
        GroupInviteJpaEntity invite = activeInvite(inviteId);
        GroupJpaEntity group = invite.getGroup();
        UserJpaEntity user = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("User not found"));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw new DomainException("User not found");
        }

        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(group.getId(), requesterId).orElse(null);
        if (member != null && member.isActive()) {
            return toMemberResponse(member);
        }

        LocalDateTime now = LocalDateTime.now();
        if (member == null) {
            member = new GroupMemberJpaEntity();
            member.setId(UUID.randomUUID());
            member.setGroup(group);
            member.setUser(user);
            member.setCreatedAt(now);
        }
        member.setRole(GroupMemberRole.MEMBER);
        member.setActive(true);
        member.setUpdatedAt(now);
        return toMemberResponse(members.save(member));
    }

    private GroupInviteJpaEntity createInvite(GroupJpaEntity group, UUID requesterId) {
        UserJpaEntity creator = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("User not found"));
        GroupInviteJpaEntity invite = new GroupInviteJpaEntity();
        invite.setId(UUID.randomUUID());
        invite.setGroup(group);
        invite.setCreatedBy(creator);
        invite.setActive(true);
        invite.setCreatedAt(LocalDateTime.now());
        return invites.save(invite);
    }

    private GroupJpaEntity activeGroup(UUID groupId) {
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        if (!group.isActive()) {
            throw new DomainException("Group not found");
        }
        return group;
    }

    private GroupInviteJpaEntity activeInvite(UUID inviteId) {
        GroupInviteJpaEntity invite = invites.findById(inviteId)
                .orElseThrow(() -> new DomainException("Invite not found or inactive"));
        if (!invite.isActive() || !invite.getGroup().isActive()) {
            throw new DomainException("Invite not found or inactive");
        }
        return invite;
    }

    private GroupInviteResponse toResponse(GroupInviteJpaEntity invite) {
        return new GroupInviteResponse(
                invite.getId(),
                invite.getGroup().getId(),
                invite.getCreatedBy().getId(),
                invite.isActive(),
                invite.getCreatedAt()
        );
    }

    private GroupMemberResponse toMemberResponse(GroupMemberJpaEntity member) {
        return new GroupMemberResponse(
                member.getId(),
                member.getGroup().getId(),
                member.getUser().getId(),
                member.getUser().getNickname(),
                member.getRole(),
                member.isActive()
        );
    }
}
