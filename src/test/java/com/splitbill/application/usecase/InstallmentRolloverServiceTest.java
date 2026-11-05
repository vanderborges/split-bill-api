package com.splitbill.application.usecase;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.InstallmentGroupJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InstallmentRolloverServiceTest {

    private final ExpenseJpaRepository expenses = mock(ExpenseJpaRepository.class);
    private final InstallmentRolloverService service =
            new InstallmentRolloverService(expenses, mock(EventJpaRepository.class));

    private final InstallmentGroupJpaEntity series = new InstallmentGroupJpaEntity();
    private final ExpenseJpaEntity first = new ExpenseJpaEntity();

    @BeforeEach
    void setUp() {
        series.setId(UUID.randomUUID());
        first.setInstallmentGroup(series);
        first.setInstallmentNumber(1);
        first.setTotalInstallments(3);
    }

    @Test
    void nextInstallmentInsideDeletedEventDoesNotCountAsExisting() {
        ExpenseJpaEntity leftBehind = second(event(EventStatus.OPEN, LocalDateTime.now()));
        when(expenses.findByInstallmentGroupIdAndDeletedAtIsNull(series.getId()))
                .thenReturn(List.of(first, leftBehind));

        assertThat(service.pendingNextNumber(first)).contains(2);
    }

    @Test
    void nextInstallmentInsideActiveEventIsNotCreatedAgain() {
        ExpenseJpaEntity existing = second(event(EventStatus.OPEN, null));
        when(expenses.findByInstallmentGroupIdAndDeletedAtIsNull(series.getId()))
                .thenReturn(List.of(first, existing));

        assertThat(service.pendingNextNumber(first)).isEmpty();
    }

    @Test
    void neverRollsIntoDeletedEvent() {
        assertRefused(event(EventStatus.OPEN, LocalDateTime.now()));
    }

    @Test
    void neverRollsIntoClosedEvent() {
        assertRefused(event(EventStatus.CLOSED, null));
    }

    @Test
    void neverRollsIntoEventInSettlement() {
        assertRefused(event(EventStatus.SETTLING, null));
    }

    private void assertRefused(EventJpaEntity target) {
        assertThatThrownBy(() -> service.createNext(first, target, 2))
                .isInstanceOf(DomainException.class);
    }

    private ExpenseJpaEntity second(EventJpaEntity event) {
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setInstallmentGroup(series);
        expense.setInstallmentNumber(2);
        expense.setEvent(event);
        return expense;
    }

    private static EventJpaEntity event(EventStatus status, LocalDateTime deletedAt) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setStatus(status);
        event.setDeletedAt(deletedAt);
        return event;
    }
}
