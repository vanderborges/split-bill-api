package com.splitbill.domain.service;

import com.splitbill.domain.valueobject.ParticipantBalance;
import com.splitbill.domain.valueobject.ParticipantShare;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyBalanceCalculatorTest {

    private final MonthlyBalanceCalculator calculator = new MonthlyBalanceCalculator();

    @Test
    void calculatesPaidConsumedAndBalanceByParticipant() {
        UUID joao = UUID.randomUUID();
        UUID maria = UUID.randomUUID();
        UUID pedro = UUID.randomUUID();

        List<ParticipantBalance> balances = calculator.calculate(List.of(
                new MonthlyBalanceCalculator.ExpenseEntry(
                        joao,
                        new BigDecimal("120.00"),
                        List.of(
                                new ParticipantShare(joao, new BigDecimal("40.00")),
                                new ParticipantShare(maria, new BigDecimal("40.00")),
                                new ParticipantShare(pedro, new BigDecimal("40.00"))
                        )
                ),
                new MonthlyBalanceCalculator.ExpenseEntry(
                        pedro,
                        new BigDecimal("60.00"),
                        List.of(
                                new ParticipantShare(maria, new BigDecimal("30.00")),
                                new ParticipantShare(pedro, new BigDecimal("30.00"))
                        )
                )
        ));

        assertThat(balances).contains(
                new ParticipantBalance(joao, new BigDecimal("40.00"), new BigDecimal("120.00"), new BigDecimal("80.00")),
                new ParticipantBalance(maria, new BigDecimal("70.00"), new BigDecimal("0.00"), new BigDecimal("-70.00")),
                new ParticipantBalance(pedro, new BigDecimal("70.00"), new BigDecimal("60.00"), new BigDecimal("-10.00"))
        );
    }
}
