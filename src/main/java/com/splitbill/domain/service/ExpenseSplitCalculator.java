package com.splitbill.domain.service;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.ParticipantShare;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ExpenseSplitCalculator {

    private static final int MONEY_SCALE = 2;

    public List<ParticipantShare> splitEqually(BigDecimal amount, List<UUID> participantIds) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException("Expense amount must be greater than zero");
        }
        if (participantIds == null || participantIds.isEmpty()) {
            throw new DomainException("Expense must have at least one participant");
        }

        BigDecimal normalizedAmount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal baseShare = normalizedAmount
                .divide(BigDecimal.valueOf(participantIds.size()), MONEY_SCALE, RoundingMode.HALF_UP);

        List<ParticipantShare> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        for (int index = 0; index < participantIds.size(); index++) {
            BigDecimal share = index == participantIds.size() - 1
                    ? normalizedAmount.subtract(allocated).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : baseShare;

            shares.add(new ParticipantShare(participantIds.get(index), share));
            allocated = allocated.add(share).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        return shares;
    }
}
