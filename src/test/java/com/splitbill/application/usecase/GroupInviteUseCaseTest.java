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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupInviteUseCaseTest {

    private final GroupInviteJpaRepository invites = mock(GroupInviteJpaRepository.class);
    private final GroupJpaRepository groups = mock(GroupJpaRepository.class);
    private final GroupMemberJpaRepository members = mock(GroupMemberJpaRepository.class);
    private final UserJpaRepository users = mock(UserJpaRepository.class);
    private final GroupUseCase groupRules = mock(GroupUseCase.class);
    private final GroupInviteUseCase useCase = new GroupInviteUseCase(invites, groups, members, users, groupRules);

    private final UUID groupId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final GroupJpaEntity group = activeGroup();
    private final UserJpaEntity admin = activeUser(adminId);

    @BeforeEach
    void setUp() {
        when(groups.findById(groupId)).thenReturn(Optional.of(group));
        when(users.findById(adminId)).thenReturn(Optional.of(admin));
        doNothing().when(groupRules).requireAdmin(groupId, adminId);
        when(invites.save(any(GroupInviteJpaEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(members.save(any(GroupMemberJpaEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void getOrCreateCreatesInviteWhenNoneActive() {
        when(invites.findFirstByGroupIdAndActiveTrueOrderByCreatedAtDesc(groupId)).thenReturn(Optional.empty());

        GroupInviteResponse response = useCase.getOrCreate(groupId, adminId);

        assertThat(response.groupId()).isEqualTo(groupId);
        assertThat(response.createdByUserId()).isEqualTo(adminId);
        assertThat(response.active()).isTrue();
    }

    @Test
    void getOrCreateReturnsExistingActiveInviteWithoutCreatingAnother() {
        GroupInviteJpaEntity existing = invite(group, admin, true);
        when(invites.findFirstByGroupIdAndActiveTrueOrderByCreatedAtDesc(groupId)).thenReturn(Optional.of(existing));

        GroupInviteResponse first = useCase.getOrCreate(groupId, adminId);
        GroupInviteResponse second = useCase.getOrCreate(groupId, adminId);

        assertThat(first.id()).isEqualTo(existing.getId());
        assertThat(second.id()).isEqualTo(existing.getId());
        verify(invites, never()).save(any());
    }

    @Test
    void regenerateDeactivatesCurrentInviteAndCreatesNewOne() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        when(invites.findFirstByGroupIdAndActiveTrueOrderByCreatedAtDesc(groupId)).thenReturn(Optional.of(current));

        GroupInviteResponse response = useCase.regenerate(groupId, adminId);

        assertThat(current.isActive()).isFalse();
        assertThat(response.id()).isNotEqualTo(current.getId());
        assertThat(response.active()).isTrue();
    }

    @Test
    void previewRejectsInviteFromInactiveGroup() {
        GroupJpaEntity inactiveGroup = activeGroup();
        inactiveGroup.setActive(false);
        GroupInviteJpaEntity current = invite(inactiveGroup, admin, true);
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> useCase.preview(current.getId()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void joinCreatesNewMemberWithRoleMemberNeverAdmin() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        UUID joinerId = UUID.randomUUID();
        UserJpaEntity joiner = activeUser(joinerId);
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));
        when(users.findById(joinerId)).thenReturn(Optional.of(joiner));
        when(members.findByGroupIdAndUserId(groupId, joinerId)).thenReturn(Optional.empty());

        GroupMemberResponse response = useCase.join(current.getId(), joinerId);

        assertThat(response.role()).isEqualTo(GroupMemberRole.MEMBER);
        assertThat(response.active()).isTrue();
        assertThat(response.userId()).isEqualTo(joinerId);
    }

    @Test
    void joinIsIdempotentForAlreadyActiveMember() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        UUID memberId = UUID.randomUUID();
        UserJpaEntity memberUser = activeUser(memberId);
        GroupMemberJpaEntity existingMember = new GroupMemberJpaEntity();
        existingMember.setId(UUID.randomUUID());
        existingMember.setGroup(group);
        existingMember.setUser(memberUser);
        existingMember.setRole(GroupMemberRole.ADMIN);
        existingMember.setActive(true);
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));
        when(users.findById(memberId)).thenReturn(Optional.of(memberUser));
        when(members.findByGroupIdAndUserId(groupId, memberId)).thenReturn(Optional.of(existingMember));

        GroupMemberResponse response = useCase.join(current.getId(), memberId);

        assertThat(response.role()).isEqualTo(GroupMemberRole.ADMIN);
        verify(members, never()).save(any());
    }

    @Test
    void joinRejectsInactiveInvite() {
        GroupInviteJpaEntity current = invite(group, admin, false);
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> useCase.join(current.getId(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
        verify(members, never()).save(any());
    }

    private GroupJpaEntity activeGroup() {
        GroupJpaEntity entity = new GroupJpaEntity();
        entity.setId(groupId);
        entity.setName("Viagem Praia");
        entity.setActive(true);
        return entity;
    }

    private UserJpaEntity activeUser(UUID id) {
        UserJpaEntity user = new UserJpaEntity();
        user.setId(id);
        user.setNickname("user-" + id);
        user.setActive(true);
        return user;
    }

    private GroupInviteJpaEntity invite(GroupJpaEntity forGroup, UserJpaEntity createdBy, boolean active) {
        GroupInviteJpaEntity entity = new GroupInviteJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setGroup(forGroup);
        entity.setCreatedBy(createdBy);
        entity.setActive(active);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
