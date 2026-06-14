package com.splitbill.domain.service;

import com.splitbill.domain.valueobject.ParticipantBalance;
import com.splitbill.domain.valueobject.ParticipantShare;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MonthlyBalanceCalculator {

    private static final int MONEY_SCALE = 2;

    public List<ParticipantBalance> calculate(List<ExpenseEntry> expenses) {
        Map<UUID, Totals> totalsByUser = new LinkedHashMap<>();

        for (ExpenseEntry expense : expenses) {
            for (ParticipantShare payerShare : expense.payers()) {
                totalsByUser.computeIfAbsent(payerShare.userId(), ignored -> new Totals())
                        .addPaid(payerShare.amount());
            }

            for (ParticipantShare share : expense.shares()) {
                totalsByUser.computeIfAbsent(share.userId(), ignored -> new Totals())
                        .addConsumed(share.amount());
            }
        }

        return totalsByUser.entrySet().stream()
                .map(entry -> {
                    Totals totals = entry.getValue();
                    BigDecimal balance = totals.paid.subtract(totals.consumed)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    return new ParticipantBalance(entry.getKey(), totals.consumed, totals.paid, balance);
                })
                .toList();
    }

    public record ExpenseEntry(UUID payerId, BigDecimal amount, List<ParticipantShare> shares) {
        public List<ParticipantShare> payers() {
            return List.of(new ParticipantShare(payerId, amount));
        }
    }

    private static final class Totals {
        private BigDecimal consumed = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal paid = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        private void addConsumed(BigDecimal amount) {
            consumed = consumed.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void addPaid(BigDecimal amount) {
            paid = paid.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
    }
}
