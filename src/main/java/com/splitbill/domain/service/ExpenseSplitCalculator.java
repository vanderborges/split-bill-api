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
            throw new DomainException("A despesa precisa ter ao menos um participante.");
        }
        return splitByShares(amount, participantIds.stream()
                .map(userId -> new ParticipantSplit(userId, 1, null))
                .toList());
    }

    public List<ParticipantShare> splitByShares(BigDecimal amount, List<ParticipantSplit> participants) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException("O valor da despesa precisa ser maior que zero.");
        }
        if (participants == null || participants.isEmpty()) {
            throw new DomainException("A despesa precisa ter ao menos um participante.");
        }
        if (participants.stream().anyMatch(participant -> participant.fixedAmount() != null)) {
            return splitByFixedAmounts(amount, participants);
        }
        if (participants.stream().anyMatch(participant -> participant.shareCount() <= 0)) {
            throw new DomainException("A cota do participante precisa ser maior que zero.");
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

    /**
     * Divisao por valor: cada participante tem o valor informado na mao. Todos
     * precisam ter valor (nada de misturar com cotas) e a soma tem que bater
     * exatamente com o total da despesa.
     */
    private List<ParticipantShare> splitByFixedAmounts(BigDecimal amount, List<ParticipantSplit> participants) {
        if (participants.stream().anyMatch(participant -> participant.fixedAmount() == null)) {
            throw new DomainException("Informe o valor de todos os participantes.");
        }
        if (participants.stream().anyMatch(participant -> participant.fixedAmount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new DomainException("O valor de cada participante precisa ser maior que zero.");
        }
        BigDecimal expected = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal total = participants.stream()
                .map(participant -> participant.fixedAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        if (total.compareTo(expected) != 0) {
            throw new DomainException("A soma dos valores dos participantes precisa bater com o valor da despesa.");
        }
        return participants.stream()
                .map(participant -> new ParticipantShare(
                        participant.userId(),
                        participant.fixedAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        1,
                        participant.shareDescription()
                ))
                .toList();
    }
}
