package com.splitbill.infrastructure.scheduling;

import com.splitbill.application.usecase.EventUseCase;
import com.splitbill.application.usecase.NotificationUseCase;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GroupAutoSettlementSchedulerTest {

    private final GroupJpaRepository groups = mock(GroupJpaRepository.class);
    private final EventUseCase events = mock(EventUseCase.class);
    private final NotificationUseCase notifications = mock(NotificationUseCase.class);
    private final GroupAutoSettlementScheduler scheduler =
            new GroupAutoSettlementScheduler(groups, events, notifications);

    @Test
    void processGroupSendsBillingAlertForEachEventThatWasStarted() {
        GroupJpaEntity group = groupWithCreator();
        EventJpaEntity eventA = event(group);
        EventJpaEntity eventB = event(group);
        when(events.autoStartSettlementForGroup(group)).thenReturn(List.of(eventA, eventB));

        scheduler.processGroup(group);

        verify(notifications).sendBillingAlertCore(eventA, group.getCreatedBy());
        verify(notifications).sendBillingAlertCore(eventB, group.getCreatedBy());
    }

    @Test
    void processGroupDoesNothingWhenNoEventWasStarted() {
        GroupJpaEntity group = groupWithCreator();
        when(events.autoStartSettlementForGroup(group)).thenReturn(List.of());

        scheduler.processGroup(group);

        verify(notifications, never()).sendBillingAlertCore(any(), any());
    }

    @Test
    void processGroupKeepsGoingWhenOneEventHasNothingPendingToCharge() {
        GroupJpaEntity group = groupWithCreator();
        EventJpaEntity eventWithNothingPending = event(group);
        EventJpaEntity eventWithPending = event(group);
        when(events.autoStartSettlementForGroup(group))
                .thenReturn(List.of(eventWithNothingPending, eventWithPending));
        when(notifications.sendBillingAlertCore(eq(eventWithNothingPending), any()))
                .thenThrow(new DomainException("Nao ha pagamentos pendentes para cobrar neste evento"));

        scheduler.processGroup(group);

        verify(notifications).sendBillingAlertCore(eventWithPending, group.getCreatedBy());
    }

    private GroupJpaEntity groupWithCreator() {
        UserJpaEntity creator = new UserJpaEntity();
        creator.setId(UUID.randomUUID());
        creator.setNickname("Admin");
        creator.setActive(true);

        GroupJpaEntity group = new GroupJpaEntity();
        group.setId(UUID.randomUUID());
        group.setName("Grupo Teste");
        group.setActive(true);
        group.setCreatedBy(creator);
        group.setAutoSettlementDay(5);
        return group;
    }

    private EventJpaEntity event(GroupJpaEntity group) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setGroup(group);
        return event;
    }
}
