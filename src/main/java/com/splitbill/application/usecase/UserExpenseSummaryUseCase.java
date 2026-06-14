package com.splitbill.application.usecase;

import com.splitbill.application.dto.ExpenseParticipantResponse;
import com.splitbill.application.dto.ExpensePayerResponse;
import com.splitbill.application.dto.ExpenseResponse;
import com.splitbill.application.dto.UserExpenseSummaryResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpensePayerJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class UserExpenseSummaryUseCase {

    private static final int MONEY_SCALE = 2;

    private final ExpenseJpaRepository expenses;
    private final UserJpaRepository users;

    public UserExpenseSummaryUseCase(ExpenseJpaRepository expenses, UserJpaRepository users) {
        this.expenses = expenses;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserExpenseSummaryResponse get(
            UUID userId,
            LocalDate from,
            LocalDate to,
            String category,
            UUID eventId
    ) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(2999, 12, 31) : to;
        if (start.isAfter(end)) {
            throw new DomainException("from must be before or equal to to");
        }

        List<ExpenseJpaEntity> filtered = expenses.findByExpenseDateBetweenAndDeletedAtIsNull(start, end).stream()
                .filter(expense -> eventId == null || expense.getEvent().getId().equals(eventId))
                .filter(expense -> category == null || expense.getCategory().equalsIgnoreCase(category))
                .filter(expense -> hasUser(expense, userId))
                .sorted(Comparator.comparing(ExpenseJpaEntity::getExpenseDate))
                .toList();

        BigDecimal totalConsumed = filtered.stream()
                .flatMap(expense -> expense.getParticipants().stream())
                .filter(participant -> participant.getUser().getId().equals(userId))
                .map(participant -> participant.getShareAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);

        BigDecimal totalPaid = filtered.stream()
                .flatMap(expense -> expense.getPayers().stream())
                .filter(payer -> payer.getUser().getId().equals(userId))
                .map(payer -> payer.getPaidAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);

        BigDecimal balance = totalPaid.subtract(totalConsumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new UserExpenseSummaryResponse(
                user.getId(),
                user.getNickname(),
                start,
                end,
                totalConsumed,
                totalPaid,
                balance,
                filtered.stream().map(this::toResponse).toList()
        );
    }

    private boolean hasUser(ExpenseJpaEntity expense, UUID userId) {
        return expense.getParticipants().stream().anyMatch(participant -> participant.getUser().getId().equals(userId))
                || expense.getPayers().stream().anyMatch(payer -> payer.getUser().getId().equals(userId));
    }

    private ExpenseResponse toResponse(ExpenseJpaEntity expense) {
        ExpensePayerJpaEntity mainPayer = expense.getPayers().isEmpty()
                ? null
                : expense.getPayers().get(0);
        return new ExpenseResponse(
                expense.getId(),
                expense.getDescription(),
                expense.getAmount(),
                expense.getExpenseDate(),
                expense.getCategory(),
                mainPayer == null ? expense.getPayer().getId() : mainPayer.getUser().getId(),
                mainPayer == null ? expense.getPayer().getNickname() : mainPayer.getUser().getNickname(),
                expense.getMonth() == null ? null : expense.getMonth().getId(),
                expense.getEvent().getId(),
                expense.getSourceEvent() == null ? null : expense.getSourceEvent().getId(),
                expense.getInstallmentGroup() == null ? null : expense.getInstallmentGroup().getId(),
                expense.getInstallmentNumber(),
                expense.getTotalInstallments(),
                expense.getPayers().stream()
                        .map(payer -> new ExpensePayerResponse(
                                payer.getUser().getId(),
                                payer.getUser().getNickname(),
                                payer.getPaidAmount()
                        ))
                        .toList(),
                expense.getParticipants().stream()
                        .map(participant -> new ExpenseParticipantResponse(
                                participant.getUser().getId(),
                                participant.getUser().getNickname(),
                                participant.getShareAmount()
                        ))
                        .toList()
        );
    }
}
