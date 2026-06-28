package com.splitbill.domain.service;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.ParticipantShare;
import com.splitbill.domain.valueobject.ParticipantSplit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ExpenseSplitCalculator {

    private static final int MONEY_SCALE = 2;

    public List<ParticipantShare> splitEqually(BigDecimal amount, List<UUID> participantIds) {
        if (participantIds == null) {
            throw new DomainException("Expense must have at least one participant");
        }
        return splitByShares(amount, participantIds.stream()
                .map(userId -> new ParticipantSplit(userId, 1, null))
                .toList());
    }

    public List<ParticipantShare> splitByShares(BigDecimal amount, List<ParticipantSplit> participants) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException("Expense amount must be greater than zero");
        }
        if (participants == null || participants.isEmpty()) {
            throw new DomainException("Expense must have at least one participant");
        }
        if (participants.stream().anyMatch(participant -> participant.shareCount() <= 0)) {
            throw new DomainException("Participant share count must be greater than zero");
        }

        BigDecimal normalizedAmount = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        int totalShares = participants.stream()
                .mapToInt(ParticipantSplit::shareCount)
                .sum();
        BigDecimal baseShare = normalizedAmount
                .divide(BigDecimal.valueOf(totalShares), MONEY_SCALE, RoundingMode.HALF_UP);

        List<ParticipantShare> shares = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        for (int index = 0; index < participants.size(); index++) {
            ParticipantSplit participant = participants.get(index);
            BigDecimal share = index == participants.size() - 1
                    ? normalizedAmount.subtract(allocated).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : baseShare.multiply(BigDecimal.valueOf(participant.shareCount()))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

            shares.add(new ParticipantShare(
                    participant.userId(),
                    share,
                    participant.shareCount(),
                    participant.shareDescription()
            ));
            allocated = allocated.add(share).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        return shares;
    }
}
