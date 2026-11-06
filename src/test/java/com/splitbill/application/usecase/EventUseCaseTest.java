package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateEventRequest;
import com.splitbill.application.dto.EventResponse;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.application.dto.PendingInstallmentResponse;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.InstallmentGroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.MonthJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.MonthJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventUseCaseTest {

    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    private final EventJpaRepository events = mock(EventJpaRepository.class);
    private final MonthJpaRepository months = mock(MonthJpaRepository.class);
    private final ExpenseJpaRepository expenses = mock(ExpenseJpaRepository.class);
    private final GroupJpaRepository groups = mock(GroupJpaRepository.class);
    private final InstallmentRolloverService rollover = mock(InstallmentRolloverService.class);
    private final GroupMemberJpaRepository groupMembers = mock(GroupMemberJpaRepository.class);
    private final GroupUseCase groupRules = mock(GroupUseCase.class);
    private final EventUseCase useCase = new EventUseCase(
            events,
            months,
            expenses,
            mock(UserJpaRepository.class),
            mock(MonthlyReportUseCase.class),
            groups,
            groupMembers,
            groupRules,
            mock(EventSettlementUseCase.class),
            rollover
    );

    private final GroupJpaEntity group = new GroupJpaEntity();
    private final EventJpaEntity november = new EventJpaEntity();
    private final ExpenseJpaEntity course = series("Curso", 3);
    private final ExpenseJpaEntity streaming = series("Streaming", null);

    @BeforeEach
    void setUp() {
        group.setId(UUID.randomUUID());
        november.setId(UUID.randomUUID());
        november.setStatus(EventStatus.CLOSED);

        MonthJpaEntity month = new MonthJpaEntity();
        month.setId(UUID.randomUUID());
        month.setMonth(12);
        month.setYear(2026);

        when(groups.findById(group.getId())).thenReturn(Optional.of(group));
        when(months.findByMonthAndYear(12, 2026)).thenReturn(Optional.of(month));
        when(rollover.previousMonthlyEvent(group.getId(), DECEMBER)).thenReturn(Optional.of(november));
        when(expenses.findByEventIdAndDeletedAtIsNull(november.getId())).thenReturn(List.of(course, streaming));
        when(rollover.pendingNextNumber(any())).thenReturn(Optional.of(2));
    }

    @Test
    void previousMonthNoLongerOpenListsSeriesToAsk() {
        List<PendingInstallmentResponse> pending =
                useCase.pendingInstallments(group.getId(), 12, 2026, UUID.randomUUID());

        assertThat(pending).extracting(PendingInstallmentResponse::description)
                .containsExactly("Curso", "Streaming");
        assertThat(pending.get(0).installmentNumber()).isEqualTo(2);
        assertThat(pending.get(0).totalInstallments()).isEqualTo(3);
        assertThat(pending.get(1).subscription()).isTrue();
    }

    @Test
    void previousMonthStillOpenAsksNothingBecauseSeriesComeAlone() {
        november.setStatus(EventStatus.OPEN);

        assertThat(useCase.pendingInstallments(group.getId(), 12, 2026, UUID.randomUUID())).isEmpty();
    }

    @Test
    void seriesAlreadyPlacedOrFinishedAreNotAsked() {
        when(rollover.pendingNextNumber(any())).thenReturn(Optional.empty());

        assertThat(useCase.pendingInstallments(group.getId(), 12, 2026, UUID.randomUUID())).isEmpty();
    }

    @Test
    void createContinuesOnlyChosenSeries() {
        useCase.create(request(List.of(course.getInstallmentGroup().getId())), UUID.randomUUID());

        verify(rollover).createNext(eq(course), any(), eq(2));
        verify(rollover, never()).createNext(eq(streaming), any(), any(Integer.class));
    }

    @Test
    void uncheckingEverythingCreatesTheMonthEmpty() {
        useCase.create(request(List.of()), UUID.randomUUID());

        verify(events).save(any());
        verify(rollover, never()).createNext(any(), any(), any(Integer.class));
    }

    @Test
    void oldAppWithoutTheFieldKeepsCreatingWithoutSeries() {
        useCase.create(request(null), UUID.randomUUID());

        verify(events).save(any());
        verify(rollover, never()).createNext(any(), any(), any(Integer.class));
    }

    @Test
    void rolloverSkipsMonthInSettlementAndCreatesTheNextOne() {
        assertThat(rolloverTargetWithDecemberIn(EventStatus.SETTLING).getMonth().getYear()).isEqualTo(2027);
    }

    @Test
    void rolloverSkipsClosedMonthAndCreatesTheNextOne() {
        EventJpaEntity target = rolloverTargetWithDecemberIn(EventStatus.CLOSED);

        assertThat(target.getMonth().getMonth()).isEqualTo(1);
        assertThat(target.getStatus()).isEqualTo(EventStatus.OPEN);
    }

    @Test
    void rolloverUsesNearestOpenMonth() {
        EventJpaEntity target = rolloverTargetWithDecemberIn(EventStatus.OPEN);

        assertThat(target.getMonth().getMonth()).isEqualTo(12);
        verify(events, never()).save(any());
    }

    private EventJpaEntity rolloverTargetWithDecemberIn(EventStatus decemberStatus) {
        november.setStatus(EventStatus.OPEN);
        november.setType(EventType.MONTHLY);
        november.setGroup(group);
        november.setMonth(month(11, 2026));

        EventJpaEntity december = new EventJpaEntity();
        december.setId(UUID.randomUUID());
        december.setType(EventType.MONTHLY);
        december.setStatus(decemberStatus);
        december.setGroup(group);
        december.setMonth(month(12, 2026));

        when(events.findById(november.getId())).thenReturn(Optional.of(november));
        when(events.findByGroupIdAndDeletedAtIsNull(group.getId())).thenReturn(List.of(november, december));
        when(months.findByMonthAndYear(1, 2027)).thenReturn(Optional.of(month(1, 2027)));
        when(events.save(any())).thenAnswer(call -> call.getArgument(0));
        when(expenses.findByEventIdAndDeletedAtIsNull(november.getId())).thenReturn(List.of(course));

        useCase.startSettlement(november.getId(), null, UUID.randomUUID());

        ArgumentCaptor<EventJpaEntity> target = ArgumentCaptor.forClass(EventJpaEntity.class);
        verify(rollover).createNext(eq(course), target.capture(), eq(2));
        return target.getValue();
    }

    private static MonthJpaEntity month(int number, int year) {
        MonthJpaEntity month = new MonthJpaEntity();
        month.setId(UUID.randomUUID());
        month.setMonth(number);
        month.setYear(year);
        return month;
    }

    @Test
    void eventsOfDeletedGroupDoNotAppearInAnyList() {
        GroupJpaEntity deleted = new GroupJpaEntity();
        deleted.setId(UUID.randomUUID());
        deleted.setActive(false);
        group.setActive(true);
        EventJpaEntity visible = listed(group, "11/2026");
        EventJpaEntity hidden = listed(deleted, "10/2026");
        UUID viewer = UUID.randomUUID();
        GroupMemberJpaEntity inActive = membership(viewer, group);
        GroupMemberJpaEntity inDeleted = membership(viewer, deleted);
        when(groupMembers.findByUserId(viewer)).thenReturn(List.of(inActive, inDeleted));
        when(events.findByGroupIdInAndDeletedAtIsNull(any())).thenReturn(List.of(visible, hidden));
        when(events.findByGroupIdAndDeletedAtIsNull(deleted.getId())).thenReturn(List.of(hidden));
        when(groupRules.canSeeEvent(any(), any(), any())).thenReturn(true);

        assertThat(useCase.list(viewer, null)).extracting(EventResponse::name).containsExactly("11/2026");
        // Extrato (inclui grupos de onde a pessoa saiu) e filtro pelo grupo excluido.
        assertThat(useCase.list(viewer, null, true)).extracting(EventResponse::name).containsExactly("11/2026");
        assertThat(useCase.list(viewer, deleted.getId(), true)).isEmpty();
    }

    private static EventJpaEntity listed(GroupJpaEntity owner, String name) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(name);
        event.setType(EventType.MONTHLY);
        event.setStatus(EventStatus.OPEN);
        event.setGroup(owner);
        event.setCreatedAt(java.time.LocalDateTime.now());
        return event;
    }

    private static GroupMemberJpaEntity membership(UUID userId, GroupJpaEntity owner) {
        GroupMemberJpaEntity member = new GroupMemberJpaEntity();
        member.setGroup(owner);
        member.setActive(true);
        member.setRole(com.splitbill.domain.valueobject.GroupMemberRole.MEMBER);
        return member;
    }

    private CreateEventRequest request(List<UUID> continueIds) {
        return new CreateEventRequest(null, null, EventType.MONTHLY, null, 12, 2026, group.getId(), continueIds);
    }

    private static ExpenseJpaEntity series(String description, Integer total) {
        InstallmentGroupJpaEntity installmentGroup = new InstallmentGroupJpaEntity();
        installmentGroup.setId(UUID.randomUUID());
        installmentGroup.setDescription(description);
        installmentGroup.setTotalAmount(new BigDecimal("100.00"));
        installmentGroup.setSubscription(total == null);
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setInstallmentGroup(installmentGroup);
        expense.setInstallmentNumber(1);
        expense.setTotalInstallments(total);
        return expense;
    }
}
