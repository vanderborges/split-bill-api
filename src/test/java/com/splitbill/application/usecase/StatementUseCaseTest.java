package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.dto.StatementResponse;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventSettlementJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementUseCaseTest {

    private final EventUseCase events = mock(EventUseCase.class);
    private final EventJpaRepository eventRows = mock(EventJpaRepository.class);
    private final MonthlyReportUseCase reports = mock(MonthlyReportUseCase.class);
    private final EventSettlementUseCase settlements = mock(EventSettlementUseCase.class);
    private final GroupUseCase groupRules = mock(GroupUseCase.class);
    private final GroupMemberJpaRepository groupMembers = mock(GroupMemberJpaRepository.class);
    private final ExpenseJpaRepository expenseRows = mock(ExpenseJpaRepository.class);
    private final EventSettlementJpaRepository settlementRows = mock(EventSettlementJpaRepository.class);
    private final StatementUseCase useCase = new StatementUseCase(
            events, eventRows, expenseRows, settlementRows, reports, settlements, groupRules, groupMembers);

    private final UUID viewer = UUID.randomUUID();
    private final List<EventJpaEntity> allEvents = new java.util.ArrayList<>();

    @Test
    void loadsEachVisibleEventOnceAndChecksAccessOnce() {
        GroupJpaEntity group = group();
        EventJpaEntity visible = event(group, "11/2026");
        EventJpaEntity hidden = event(group, "10/2026");
        GroupMemberJpaEntity member = member(group, true, GroupMemberRole.ADMIN);
        when(events.list(viewer, null, true)).thenReturn(List.of(listed(visible), listed(hidden)));
        when(groupMembers.findByUserId(viewer)).thenReturn(List.of(member));
        when(groupRules.involvedEventIds(viewer)).thenReturn(Set.of());
        when(groupRules.canSeeEvent(member, visible, Set.of())).thenReturn(true);
        when(groupRules.canSeeEvent(member, hidden, Set.of())).thenReturn(false);
        when(reports.reportWithBalances(eq(visible), any(), any()))
                .thenReturn(new MonthlyReportUseCase.EventReport(null, List.of()));
        when(settlements.syncWithBalances(eq(visible), any(), any())).thenReturn(List.of());

        StatementResponse response = useCase.load(viewer, null);

        assertThat(response.events()).extracting(StatementResponse.StatementEventResponse::eventId)
                .containsExactly(visible.getId());
        assertThat(response.groupRoles()).containsEntry(group.getId(), "ADMIN");
        // Uma consulta de "eventos com despesa da pessoa" por chamada, nao por evento.
        verify(groupRules, times(1)).involvedEventIds(viewer);
        verify(reports, never()).reportWithBalances(eq(hidden), any(), any());
        verify(groupRules, never()).requireEventAccess(any(), any());
    }

    @Test
    void groupTheViewerLeftShowsEventsWithoutRole() {
        GroupJpaEntity group = group();
        EventJpaEntity event = event(group, "09/2026");
        GroupMemberJpaEntity removed = member(group, false, GroupMemberRole.MEMBER);
        when(events.list(viewer, null, true)).thenReturn(List.of(listed(event)));
        when(groupMembers.findByUserId(viewer)).thenReturn(List.of(removed));
        when(groupRules.involvedEventIds(viewer)).thenReturn(Set.of(event.getId()));
        when(groupRules.canSeeEvent(removed, event, Set.of(event.getId()))).thenReturn(true);
        when(reports.reportWithBalances(eq(event), any(), any())).thenReturn(new MonthlyReportUseCase.EventReport(null, List.of()));

        StatementResponse response = useCase.load(viewer, null);

        assertThat(response.events()).hasSize(1);
        assertThat(response.groupRoles()).containsKey(group.getId());
        assertThat(response.groupRoles().get(group.getId())).isNull();
    }

    private EventJpaEntity event(GroupJpaEntity group, String name) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(name);
        event.setType(EventType.MONTHLY);
        event.setStatus(EventStatus.OPEN);
        event.setGroup(group);
        event.setCreatedAt(LocalDateTime.now());
        allEvents.add(event);
        when(eventRows.findAllById(any())).thenReturn(allEvents);
        return event;
    }

    private static EventResponse listed(EventJpaEntity event) {
        return new EventResponse(event.getId(), event.getName(), null, event.getType(), event.getStatus(),
                event.getGroup().getId(), null, null, null, event.getCreatedAt(), null, null, null);
    }

    private static GroupJpaEntity group() {
        GroupJpaEntity group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        group.setActive(true);
        return group;
    }

    private static GroupMemberJpaEntity member(GroupJpaEntity group, boolean active, GroupMemberRole role) {
        GroupMemberJpaEntity member = new GroupMemberJpaEntity();
        member.setGroup(group);
        member.setActive(active);
        member.setRole(role);
        return member;
    }
}
