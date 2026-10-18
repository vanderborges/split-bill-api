package com.splitbill.application.usecase;

import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupInviteJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaceholderUseCaseTest {

    private final GroupMemberJpaRepository members = mock(GroupMemberJpaRepository.class);
    private final PlaceholderUseCase useCase = new PlaceholderUseCase(
            mock(UserJpaRepository.class),
            mock(GroupJpaRepository.class),
            members,
            mock(GroupUseCase.class),
            mock(EntityManager.class)
    );

    private final GroupJpaEntity group = group();
    private final EventJpaEntity event = event();
    private final EventJpaEntity otherEvent = event();

    @Test
    void groupInviteOffersOnlyFixedPlaceholders() {
        GroupMemberJpaEntity fixedPlaceholder = member("Carla", true, null);
        GroupMemberJpaEntity temporaryPlaceholder = member("Beto", true, event);
        GroupMemberJpaEntity realMember = member("Ana", false, null);
        when(members.findByGroupIdAndActiveTrue(group.getId()))
                .thenReturn(List.of(fixedPlaceholder, temporaryPlaceholder, realMember));

        List<GroupMemberJpaEntity> claimable = useCase.claimableFor(invite(null));

        assertThat(claimable).containsExactly(fixedPlaceholder);
    }

    @Test
    void temporaryInviteOffersOnlyPlaceholdersOfItsEventSortedByName() {
        GroupMemberJpaEntity fixedPlaceholder = member("Carla", true, null);
        GroupMemberJpaEntity zeca = member("Zeca", true, event);
        GroupMemberJpaEntity beto = member("beto", true, event);
        GroupMemberJpaEntity fromOtherEvent = member("Dani", true, otherEvent);
        when(members.findByGroupIdAndActiveTrue(group.getId()))
                .thenReturn(List.of(fixedPlaceholder, zeca, beto, fromOtherEvent));

        List<GroupMemberJpaEntity> claimable = useCase.claimableFor(invite(event));

        assertThat(claimable).containsExactly(beto, zeca);
    }

    private GroupMemberJpaEntity member(String nickname, boolean placeholder, EventJpaEntity temporaryEvent) {
        UserJpaEntity user = new UserJpaEntity();
        user.setId(UUID.randomUUID());
        user.setNickname(nickname);
        user.setActive(true);
        user.setPlaceholder(placeholder);
        GroupMemberJpaEntity member = new GroupMemberJpaEntity();
        member.setId(UUID.randomUUID());
        member.setGroup(group);
        member.setUser(user);
        member.setActive(true);
        member.setTemporary(temporaryEvent != null);
        member.setTemporaryEvent(temporaryEvent);
        return member;
    }

    private GroupInviteJpaEntity invite(EventJpaEntity forEvent) {
        GroupInviteJpaEntity invite = new GroupInviteJpaEntity();
        invite.setId(UUID.randomUUID());
        invite.setGroup(group);
        invite.setEvent(forEvent);
        invite.setActive(true);
        return invite;
    }

    private static GroupJpaEntity group() {
        GroupJpaEntity entity = new GroupJpaEntity();
        entity.setId(UUID.randomUUID());
        entity.setActive(true);
        return entity;
    }

    private static EventJpaEntity event() {
        EventJpaEntity entity = new EventJpaEntity();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
