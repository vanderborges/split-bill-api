package com.splitbill.domain.service;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.ParticipantShare;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpenseSplitCalculatorTest {

    private final ExpenseSplitCalculator calculator = new ExpenseSplitCalculator();

    @Test
    void splitsExpenseEquallyAndKeepsTotalExact() {
        UUID joao = UUID.randomUUID();
        UUID maria = UUID.randomUUID();
        UUID pedro = UUID.randomUUID();

        List<ParticipantShare> shares = calculator.splitEqually(
                new BigDecimal("200.00"),
                List.of(joao, maria, pedro)
        );

        assertThat(shares)
                .extracting(ParticipantShare::amount)
                .containsExactly(new BigDecimal("66.67"), new BigDecimal("66.67"), new BigDecimal("66.66"));

        BigDecimal total = shares.stream()
                .map(ParticipantShare::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(total).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    void rejectsExpenseWithoutParticipants() {
        assertThatThrownBy(() -> calculator.splitEqually(new BigDecimal("10.00"), List.of()))
                .isInstanceOf(DomainException.class)
                .hasMessage("Expense must have at least one participant");
    }
}
