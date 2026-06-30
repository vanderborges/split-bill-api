package com.splitbill.domain.service;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.ParticipantShare;
import com.splitbill.domain.valueobject.ParticipantSplit;
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

    @Test
    void splitsExpenseByParticipantShareCounts() {
        UUID vander = UUID.randomUUID();
        UUID de = UUID.randomUUID();
        UUID andy = UUID.randomUUID();
        UUID rafa = UUID.randomUUID();

        List<ParticipantShare> shares = calculator.splitByShares(
                new BigDecimal("100.00"),
                List.of(
                        new ParticipantSplit(vander, 1, null),
                        new ParticipantSplit(de, 1, null),
                        new ParticipantSplit(andy, 1, null),
                        new ParticipantSplit(rafa, 2, "Rafa e Gertrudes")
                )
        );

        assertThat(shares)
                .extracting(ParticipantShare::amount)
                .containsExactly(
                        new BigDecimal("20.00"),
                        new BigDecimal("20.00"),
                        new BigDecimal("20.00"),
                        new BigDecimal("40.00")
                );
        assertThat(shares.get(3).shareCount()).isEqualTo(2);
        assertThat(shares.get(3).shareDescription()).isEqualTo("Rafa e Gertrudes");
    }

    @Test
    void keepsShareCountsWhenAllParticipantsHaveTheSameExtraShares() {
        UUID rafa = UUID.randomUUID();
        UUID de = UUID.randomUUID();

        List<ParticipantShare> shares = calculator.splitByShares(
                new BigDecimal("100.00"),
                List.of(
                        new ParticipantSplit(rafa, 2, "Rafa e Gertrudes"),
                        new ParticipantSplit(de, 2, "De e convidado")
                )
        );

        assertThat(shares)
                .extracting(ParticipantShare::amount)
                .containsExactly(new BigDecimal("50.00"), new BigDecimal("50.00"));
        assertThat(shares)
                .extracting(ParticipantShare::shareCount)
                .containsExactly(2, 2);
    }

    @Test
    void splitsNineParticipantsWithTwoExtraSharesIntoElevenShares() {
        List<UUID> users = List.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        List<ParticipantShare> shares = calculator.splitByShares(
                new BigDecimal("110.00"),
                List.of(
                        new ParticipantSplit(users.get(0), 1, null),
                        new ParticipantSplit(users.get(1), 1, null),
                        new ParticipantSplit(users.get(2), 1, null),
                        new ParticipantSplit(users.get(3), 1, null),
                        new ParticipantSplit(users.get(4), 1, null),
                        new ParticipantSplit(users.get(5), 1, null),
                        new ParticipantSplit(users.get(6), 1, null),
                        new ParticipantSplit(users.get(7), 2, "Usuario 8 e convidado"),
                        new ParticipantSplit(users.get(8), 2, "Usuario 9 e convidado")
                )
        );

        assertThat(shares)
                .extracting(ParticipantShare::amount)
                .containsExactly(
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("10.00"),
                        new BigDecimal("20.00"),
                        new BigDecimal("20.00")
                );
        assertThat(shares)
                .extracting(ParticipantShare::shareCount)
                .containsExactly(1, 1, 1, 1, 1, 1, 1, 2, 2);
    }
}
