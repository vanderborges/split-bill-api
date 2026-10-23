package com.splitbill.application.usecase;

import com.splitbill.application.dto.GroupInvitePreviewResponse;
import com.splitbill.application.dto.GroupInviteResponse;
import com.splitbill.application.dto.GroupMemberResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.GroupInviteJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.NotificationJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupInviteJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.NotificationJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupInviteUseCaseTest {

    private final GroupInviteJpaRepository invites = mock(GroupInviteJpaRepository.class);
    private final GroupJpaRepository groups = mock(GroupJpaRepository.class);
    private final GroupMemberJpaRepository members = mock(GroupMemberJpaRepository.class);
    private final UserJpaRepository users = mock(UserJpaRepository.class);
    private final GroupUseCase groupRules = mock(GroupUseCase.class);
    private final PlaceholderUseCase placeholders = mock(PlaceholderUseCase.class);
    private final NotificationJpaRepository notifications = mock(NotificationJpaRepository.class);
    private final GroupInviteUseCase useCase =
            new GroupInviteUseCase(invites, groups, members, users, groupRules, placeholders, notifications,
                    mock(org.springframework.context.ApplicationEventPublisher.class));

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
        when(invites.findFirstByGroupIdAndEventIsNullAndActiveTrueOrderByCreatedAtDesc(groupId)).thenReturn(Optional.empty());

        GroupInviteResponse response = useCase.getOrCreate(groupId, adminId);

        assertThat(response.groupId()).isEqualTo(groupId);
        assertThat(response.createdByUserId()).isEqualTo(adminId);
        assertThat(response.active()).isTrue();
    }

    @Test
    void getOrCreateReturnsExistingActiveInviteWithoutCreatingAnother() {
        GroupInviteJpaEntity existing = invite(group, admin, true);
        when(invites.findFirstByGroupIdAndEventIsNullAndActiveTrueOrderByCreatedAtDesc(groupId)).thenReturn(Optional.of(existing));

        GroupInviteResponse first = useCase.getOrCreate(groupId, adminId);
        GroupInviteResponse second = useCase.getOrCreate(groupId, adminId);

        assertThat(first.id()).isEqualTo(existing.getId());
        assertThat(second.id()).isEqualTo(existing.getId());
        verify(invites, never()).save(any());
    }

    @Test
    void regenerateDeactivatesCurrentInviteAndCreatesNewOne() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        when(invites.findFirstByGroupIdAndEventIsNullAndActiveTrueOrderByCreatedAtDesc(groupId)).thenReturn(Optional.of(current));

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
    void joinWithPlaceholderDelegatesToClaim() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        UUID joinerId = UUID.randomUUID();
        UUID placeholderId = UUID.randomUUID();
        UserJpaEntity joiner = activeUser(joinerId);
        GroupMemberJpaEntity claimed = new GroupMemberJpaEntity();
        claimed.setId(UUID.randomUUID());
        claimed.setGroup(group);
        claimed.setUser(joiner);
        claimed.setRole(GroupMemberRole.MEMBER);
        claimed.setActive(true);
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));
        when(users.findById(joinerId)).thenReturn(Optional.of(joiner));
        when(placeholders.claim(current, placeholderId, joiner)).thenReturn(claimed);

        GroupMemberResponse response = useCase.join(current.getId(), joinerId, placeholderId);

        assertThat(response.userId()).isEqualTo(joinerId);
        verify(placeholders).claim(current, placeholderId, joiner);
        verify(members, never()).save(any());
    }

    @Test
    void joinWithoutPlaceholderNeverClaims() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        UUID joinerId = UUID.randomUUID();
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));
        when(users.findById(joinerId)).thenReturn(Optional.of(activeUser(joinerId)));
        when(members.findByGroupIdAndUserId(groupId, joinerId)).thenReturn(Optional.empty());

        useCase.join(current.getId(), joinerId, null);

        verify(placeholders, never()).claim(any(), any(), any());
    }

    @Test
    void joinNotifiesGroupAdminsWithGroupNameInMessage() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        UUID joinerId = UUID.randomUUID();
        UserJpaEntity joiner = activeUser(joinerId);
        joiner.setNickname("Maria");
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));
        when(users.findById(joinerId)).thenReturn(Optional.of(joiner));
        when(users.getReferenceById(joinerId)).thenReturn(joiner);
        when(members.findByGroupIdAndUserId(groupId, joinerId)).thenReturn(Optional.empty());
        UserJpaEntity plainMember = activeUser(UUID.randomUUID());
        when(members.findByGroupIdAndActiveTrue(groupId)).thenReturn(List.of(
                member(admin, GroupMemberRole.ADMIN),
                member(plainMember, GroupMemberRole.MEMBER)));

        useCase.join(current.getId(), joinerId);

        verify(notifications, times(1)).save(argThat((NotificationJpaEntity n) ->
                n.getRecipient() == admin
                        && NotificationJpaEntity.TYPE_MEMBER_JOINED.equals(n.getType())
                        && n.getMessage().equals("Viagem Praia: Maria entrou no grupo.")));
    }

    @Test
    void joinDoesNotNotifyWhenAlreadyActiveMember() {
        GroupInviteJpaEntity current = invite(group, admin, true);
        UUID memberId = UUID.randomUUID();
        UserJpaEntity memberUser = activeUser(memberId);
        GroupMemberJpaEntity existing = member(memberUser, GroupMemberRole.MEMBER);
        when(invites.findById(current.getId())).thenReturn(Optional.of(current));
        when(users.findById(memberId)).thenReturn(Optional.of(memberUser));
        when(members.findByGroupIdAndUserId(groupId, memberId)).thenReturn(Optional.of(existing));

        useCase.join(current.getId(), memberId);

        verify(notifications, never()).save(any());
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

    private GroupMemberJpaEntity member(UserJpaEntity user, GroupMemberRole role) {
        GroupMemberJpaEntity entity = new GroupMemberJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setGroup(group);
        entity.setUser(user);
        entity.setRole(role);
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
