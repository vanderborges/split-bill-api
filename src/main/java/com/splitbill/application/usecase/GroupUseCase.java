package com.splitbill.application.usecase;

import com.splitbill.application.dto.AddGroupMemberRequest;
import com.splitbill.application.dto.CreateGroupRequest;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.application.dto.GroupResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.domain.valueobject.EventStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class GroupUseCase {

    private static final int MAX_ADMINS_PER_GROUP = 2;

    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository members;
    private final UserJpaRepository users;
    private final EventJpaRepository events;
    private final ExpenseJpaRepository expenses;

    public GroupUseCase(GroupJpaRepository groups, GroupMemberJpaRepository members, UserJpaRepository users, EventJpaRepository events, ExpenseJpaRepository expenses) {
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.events = events;
        this.expenses = expenses;
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> listByUser(UUID viewerUserId) {
        if (viewerUserId == null) {
            return groups.findByActiveTrue().stream().map(this::toResponse).toList();
        }
        return members.findByUserIdAndActiveTrue(viewerUserId).stream()
                .map(GroupMemberJpaEntity::getGroup)
                .filter(GroupJpaEntity::isActive)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public GroupResponse create(CreateGroupRequest request, UUID creatorId) {
        UserJpaEntity admin = activeUser(creatorId);
        LocalDateTime now = LocalDateTime.now();

        GroupJpaEntity group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        group.setName(request.name());
        group.setDescription(request.description());
        group.setCreatedBy(admin);
        group.setActive(true);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        GroupJpaEntity savedGroup = groups.save(group);

        GroupMemberJpaEntity member = new GroupMemberJpaEntity();
        member.setId(UUID.randomUUID());
        member.setGroup(savedGroup);
        member.setUser(admin);
        member.setRole(GroupMemberRole.ADMIN);
        member.setActive(true);
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        members.save(member);

        return toResponse(savedGroup);
    }

    @Transactional(readOnly = true)
    public List<GroupMemberResponse> listMembers(UUID groupId, UUID viewerUserId) {
        requireMembership(groupId, viewerUserId);
        return members.findByGroupIdAndActiveTrue(groupId).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public GroupMemberResponse addMember(UUID groupId, AddGroupMemberRequest request, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        UserJpaEntity user = activeUser(request.userId());

        if (request.role() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) >= MAX_ADMINS_PER_GROUP
                && !members.existsByGroupIdAndUserIdAndRoleAndActiveTrue(groupId, request.userId(), GroupMemberRole.ADMIN)) {
            throw new DomainException("Group can have at most two admins");
        }

        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, request.userId())
                .orElseGet(() -> {
                    GroupMemberJpaEntity created = new GroupMemberJpaEntity();
                    created.setId(UUID.randomUUID());
                    created.setGroup(group);
                    created.setUser(user);
                    created.setCreatedAt(LocalDateTime.now());
                    return created;
                });
        if (member.getRole() == GroupMemberRole.ADMIN
                && request.role() != GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) <= 1) {
            throw new DomainException("Group must have at least one admin");
        }
        member.setRole(request.role());
        member.setActive(true);
        member.setUpdatedAt(LocalDateTime.now());
        return toMemberResponse(members.save(member));
    }

    @Transactional
    public void delete(UUID groupId, UUID adminUserId) {
        requireAdmin(groupId, adminUserId);
        GroupJpaEntity group = groups.findById(groupId)
                .orElseThrow(() -> new DomainException("Group not found"));
        group.setActive(false);
        group.setUpdatedAt(LocalDateTime.now());
    }

    @Transactional
    public void removeMember(UUID groupId, UUID userId, UUID requesterId) {
        requireAdmin(groupId, requesterId);
        deactivateMember(groupId, userId);
    }

    @Transactional
    public void leave(UUID groupId, UUID userId) {
        requireMembership(groupId, userId);
        if (hasPendingBalance(groupId, userId)) {
            throw new DomainException("Settle your open event balances before leaving the group");
        }
        deactivateMember(groupId, userId);
    }

    public void requireMembership(UUID groupId, UUID userId) {
        if (userId == null) {
            return;
        }
        if (!members.existsByGroupIdAndUserIdAndActiveTrue(groupId, userId)) {
            throw new DomainException("User does not belong to this group");
        }
    }

    public void requireAdmin(UUID groupId, UUID userId) {
        if (userId == null || !members.existsByGroupIdAndUserIdAndRoleAndActiveTrue(groupId, userId, GroupMemberRole.ADMIN)) {
            throw new DomainException("Only group admins can perform this action");
        }
    }

    private void deactivateMember(UUID groupId, UUID userId) {
        GroupMemberJpaEntity member = members.findByGroupIdAndUserId(groupId, userId)
                .orElseThrow(() -> new DomainException("Group member not found"));
        if (!member.isActive()) {
            return;
        }
        if (member.getRole() == GroupMemberRole.ADMIN
                && members.countByGroupIdAndRoleAndActiveTrue(groupId, GroupMemberRole.ADMIN) <= 1) {
            throw new DomainException("Group must have at least one admin");
        }
        member.setActive(false);
        member.setUpdatedAt(LocalDateTime.now());
    }

    private boolean hasPendingBalance(UUID groupId, UUID userId) {
        return events.findByGroupIdAndDeletedAtIsNull(groupId).stream()
                .filter(event -> event.getStatus() == EventStatus.OPEN)
                .map(event -> balanceForEvent(event, userId))
                .anyMatch(balance -> balance.compareTo(java.math.BigDecimal.ZERO) != 0);
    }

    private java.math.BigDecimal balanceForEvent(EventJpaEntity event, UUID userId) {
        return expenses.findByEventIdAndDeletedAtIsNull(event.getId()).stream()
                .map(expense -> {
                    java.math.BigDecimal paid = expense.getPayers().stream()
                            .filter(payer -> payer.getUser().getId().equals(userId))
                            .map(payer -> payer.getPaidAmount())
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    java.math.BigDecimal consumed = expense.getParticipants().stream()
                            .filter(participant -> participant.getUser().getId().equals(userId))
                            .map(participant -> participant.getShareAmount())
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    return paid.subtract(consumed);
                })
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    private UserJpaEntity activeUser(UUID userId) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        if (!user.isActive() || user.getDeletedAt() != null) {
            throw new DomainException("User not found");
        }
        return user;
    }

    private GroupResponse toResponse(GroupJpaEntity group) {
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getDescription(),
                group.getCreatedBy().getId(),
                group.isActive()
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
