package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.SettlementRole;
import com.splitbill.domain.valueobject.SettlementStatus;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.EventSettlementJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventSettlementJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventSettlementUseCaseTest {

    private final EventSettlementJpaRepository settlementRows = mock(EventSettlementJpaRepository.class);
    private final EventJpaRepository events = mock(EventJpaRepository.class);
    private final MonthlyReportUseCase reports = mock(MonthlyReportUseCase.class);
    private final EventSettlementUseCase useCase = new EventSettlementUseCase(
            settlementRows,
            events,
            mock(UserJpaRepository.class),
            reports,
            mock(GroupUseCase.class)
    );

    private final EventJpaEntity event = new EventJpaEntity();
    private final UserJpaEntity payer = user("Beto");
    private final UserJpaEntity withoutExpenses = user("Caio");

    @BeforeEach
    void setUp() {
        event.setId(UUID.randomUUID());
        event.setStatus(EventStatus.SETTLING);
        when(events.findById(event.getId())).thenReturn(Optional.of(event));
        when(reports.billingRootId(any())).thenAnswer(call -> call.<UserJpaEntity>getArgument(0).getId());
    }

    @Test
    void personWhoLeftAllExpensesIsNoLongerPending() {
        when(settlementRows.findByEventId(event.getId())).thenReturn(List.of(
                row(payer, SettlementRole.DEBTOR, "50.00", SettlementStatus.PENDING),
                row(withoutExpenses, SettlementRole.DEBTOR, "5.29", SettlementStatus.PENDING)));
        when(reports.calculateBalances(event.getId())).thenReturn(List.of(balance(payer, "-50.00")));

        List<EventSettlementResponse> result = useCase.listByEvent(event.getId(), null);

        EventSettlementResponse cleared = rowOf(result, withoutExpenses);
        assertThat(cleared.role()).isEqualTo(SettlementRole.NEUTRAL);
        assertThat(cleared.amount()).isEqualByComparingTo("0.00");
        assertThat(cleared.status()).isEqualTo(SettlementStatus.PAID);
        assertThat(rowOf(result, payer).status()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void personWhoGetsAnExpenseAgainIsPendingAgain() {
        when(settlementRows.findByEventId(event.getId())).thenReturn(List.of(
                row(withoutExpenses, SettlementRole.NEUTRAL, "0.00", SettlementStatus.PAID)));
        when(reports.calculateBalances(event.getId())).thenReturn(List.of(balance(withoutExpenses, "-7.00")));

        EventSettlementResponse row = rowOf(useCase.listByEvent(event.getId(), null), withoutExpenses);

        assertThat(row.role()).isEqualTo(SettlementRole.DEBTOR);
        assertThat(row.amount()).isEqualByComparingTo("7.00");
        assertThat(row.status()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void linkedPersonFollowsHolderWhoStillHasBalance() {
        UserJpaEntity linked = user("Dé");
        when(reports.billingRootId(linked)).thenReturn(payer.getId());
        when(settlementRows.findByEventId(event.getId())).thenReturn(List.of(
                row(payer, SettlementRole.DEBTOR, "50.00", SettlementStatus.PENDING),
                row(linked, SettlementRole.DEBTOR, "20.00", SettlementStatus.PENDING)));
        when(reports.calculateBalances(event.getId())).thenReturn(List.of(balance(payer, "-50.00")));

        EventSettlementResponse row = rowOf(useCase.listByEvent(event.getId(), null), linked);

        assertThat(row.amount()).isEqualByComparingTo("20.00");
        assertThat(row.status()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void closedEventKeepsItsHistory() {
        event.setStatus(EventStatus.CLOSED);
        when(settlementRows.findByEventId(event.getId())).thenReturn(List.of(
                row(withoutExpenses, SettlementRole.DEBTOR, "5.29", SettlementStatus.PAID)));
        when(reports.calculateBalances(event.getId())).thenReturn(List.of());

        EventSettlementResponse row = rowOf(useCase.listByEvent(event.getId(), null), withoutExpenses);

        assertThat(row.role()).isEqualTo(SettlementRole.DEBTOR);
        assertThat(row.amount()).isEqualByComparingTo("5.29");
    }

    private EventSettlementResponse rowOf(List<EventSettlementResponse> rows, UserJpaEntity user) {
        return rows.stream().filter(row -> row.userId().equals(user.getId())).findFirst().orElseThrow();
    }

    private EventSettlementJpaEntity row(UserJpaEntity user, SettlementRole role, String amount, SettlementStatus status) {
        EventSettlementJpaEntity settlement = new EventSettlementJpaEntity();
        settlement.setId(UUID.randomUUID());
        settlement.setEvent(event);
        settlement.setUser(user);
        settlement.setRole(role);
        settlement.setAmount(new BigDecimal(amount));
        settlement.setStatus(status);
        return settlement;
    }

    private static MonthlyReportUseCase.BalanceResult balance(UserJpaEntity user, String amount) {
        return new MonthlyReportUseCase.BalanceResult(
                user.getId(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(amount));
    }

    private static UserJpaEntity user(String nickname) {
        UserJpaEntity user = new UserJpaEntity();
        user.setId(UUID.randomUUID());
        user.setNickname(nickname);
        return user;
    }
}
