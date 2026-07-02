package com.splitbill.application.usecase;

import com.splitbill.application.dto.ExpenseResponse;
import com.splitbill.application.dto.UserExpenseSummaryResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseParticipantJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpensePayerJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseParticipantJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpensePayerJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.domain.valueobject.GroupMemberRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserExpenseSummaryUseCase {

    private static final int MONEY_SCALE = 2;

    private final ExpenseJpaRepository expenses;
    private final ExpenseParticipantJpaRepository participants;
    private final ExpensePayerJpaRepository payers;
    private final UserJpaRepository users;
    private final GroupMemberJpaRepository groupMembers;

    public UserExpenseSummaryUseCase(
            ExpenseJpaRepository expenses,
            ExpenseParticipantJpaRepository participants,
            ExpensePayerJpaRepository payers,
            UserJpaRepository users,
            GroupMemberJpaRepository groupMembers
    ) {
        this.expenses = expenses;
        this.participants = participants;
        this.payers = payers;
        this.users = users;
        this.groupMembers = groupMembers;
    }

    @Transactional(readOnly = true)
    public UserExpenseSummaryResponse get(
            UUID groupId,
            UUID userId,
            LocalDate from,
            LocalDate to,
            String category,
            UUID eventId,
            UUID requesterId
    ) {
        if (!groupMembers.existsByGroupIdAndUserIdAndActiveTrue(groupId, requesterId)) {
            throw new DomainException("User does not belong to this group");
        }
        boolean requesterIsAdmin = groupMembers.existsByGroupIdAndUserIdAndRoleAndActiveTrue(
                groupId, requesterId, GroupMemberRole.ADMIN);
        UUID targetUserId = userId == null ? null : userId;
        if (targetUserId == null && !requesterIsAdmin) {
            targetUserId = requesterId;
        }
        if (targetUserId != null && !targetUserId.equals(requesterId) && !requesterIsAdmin) {
            throw new DomainException("Members can only view their own expense summary");
        }
        if (targetUserId != null && !groupMembers.existsByGroupIdAndUserIdAndActiveTrue(groupId, targetUserId)) {
            throw new DomainException("User does not belong to this group");
        }
        final UUID selectedUserId = targetUserId;
        UserJpaEntity user = selectedUserId == null ? null : users.findById(selectedUserId)
                .orElseThrow(() -> new DomainException("User not found"));
        LocalDate start = from == null ? LocalDate.of(2000, 1, 1) : from;
        LocalDate end = to == null ? LocalDate.of(2999, 12, 31) : to;
        if (start.isAfter(end)) {
            throw new DomainException("from must be before or equal to to");
        }

        String normalizedCategory = normalizeFilter(category);
        List<ExpenseJpaEntity> candidates = eventId == null
                ? expenses.findSummaryCandidatesByGroup(groupId, start, end)
                : expenses.findSummaryCandidatesByGroupAndEvent(groupId, eventId, start, end);

        List<ExpenseJpaEntity> categoryFiltered = candidates.stream()
                .filter(expense -> normalizedCategory == null
                        || expense.getCategory().equalsIgnoreCase(normalizedCategory))
                .toList();

        List<UUID> expenseIds = categoryFiltered.stream()
                .map(ExpenseJpaEntity::getId)
                .toList();
        Map<UUID, List<ExpenseParticipantJpaEntity>> participantsByExpenseId = expenseIds.isEmpty()
                ? Collections.emptyMap()
                : participants.findByExpenseIdsWithUser(expenseIds).stream()
                .collect(Collectors.groupingBy(participant -> participant.getExpense().getId()));
        Map<UUID, List<ExpensePayerJpaEntity>> payersByExpenseId = expenseIds.isEmpty()
                ? Collections.emptyMap()
                : payers.findByExpenseIdsWithUser(expenseIds).stream()
                .collect(Collectors.groupingBy(payer -> payer.getExpense().getId()));

        List<ExpenseJpaEntity> filtered = selectedUserId == null
                ? categoryFiltered
                : categoryFiltered.stream()
                .filter(expense -> participatesInExpense(
                        expense.getId(),
                        selectedUserId,
                        participantsByExpenseId,
                        payersByExpenseId
                ))
                .toList();

        BigDecimal totalConsumed = filtered.stream()
                .flatMap(expense -> participantsByExpenseId
                        .getOrDefault(expense.getId(), Collections.emptyList())
                        .stream())
                .filter(participant -> selectedUserId == null || participant.getUser().getId().equals(selectedUserId))
                .map(participant -> participant.getShareAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);

        BigDecimal totalPaid = filtered.stream()
                .flatMap(expense -> payersByExpenseId
                        .getOrDefault(expense.getId(), Collections.emptyList())
                        .stream())
                .filter(payer -> selectedUserId == null || payer.getUser().getId().equals(selectedUserId))
                .map(payer -> payer.getPaidAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);

        BigDecimal balance = totalPaid.subtract(totalConsumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new UserExpenseSummaryResponse(
                user == null ? null : user.getId(),
                user == null ? "Consolidado do grupo" : user.getNickname(),
                start,
                end,
                totalConsumed,
                totalPaid,
                balance,
                filtered.stream()
                        .map(this::toSummaryResponse)
                        .toList()
        );
    }

    private String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean participatesInExpense(
            UUID expenseId,
            UUID userId,
            Map<UUID, List<ExpenseParticipantJpaEntity>> participantsByExpenseId,
            Map<UUID, List<ExpensePayerJpaEntity>> payersByExpenseId
    ) {
        boolean consumed = participantsByExpenseId
                .getOrDefault(expenseId, Collections.emptyList())
                .stream()
                .anyMatch(participant -> participant.getUser().getId().equals(userId));
        boolean paid = payersByExpenseId
                .getOrDefault(expenseId, Collections.emptyList())
                .stream()
                .anyMatch(payer -> payer.getUser().getId().equals(userId));
        return consumed || paid;
    }

    private ExpenseResponse toSummaryResponse(ExpenseJpaEntity expense) {
        return new ExpenseResponse(
                expense.getId(),
                expense.getDescription(),
                expense.getAmount(),
                expense.getExpenseDate(),
                expense.getCategory(),
                expense.getPayer().getId(),
                expense.getPayer().getNickname(),
                expense.getCreatedBy().getId(),
                expense.getMonth() == null ? null : expense.getMonth().getId(),
                expense.getEvent().getId(),
                expense.getSourceEvent() == null ? null : expense.getSourceEvent().getId(),
                expense.getInstallmentGroup() == null ? null : expense.getInstallmentGroup().getId(),
                expense.getInstallmentNumber(),
                expense.getTotalInstallments(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }
}
