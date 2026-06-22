package com.splitbill.application.usecase;

import com.splitbill.application.dto.MonthlyBalanceResponse;
import com.splitbill.application.dto.MonthlyReportResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.MonthStatus;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.MonthJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.MonthJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MonthlyReportUseCase {

    private static final int MONEY_SCALE = 2;

    private final MonthJpaRepository months;
    private final EventJpaRepository events;
    private final ExpenseJpaRepository expenses;
    private final UserJpaRepository users;
    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository groupMembers;
    private final GroupUseCase groupRules;

    public MonthlyReportUseCase(
            MonthJpaRepository months,
            EventJpaRepository events,
            ExpenseJpaRepository expenses,
            UserJpaRepository users,
            GroupJpaRepository groups,
            GroupMemberJpaRepository groupMembers,
            GroupUseCase groupRules
    ) {
        this.months = months;
        this.events = events;
        this.expenses = expenses;
        this.users = users;
        this.groups = groups;
        this.groupMembers = groupMembers;
        this.groupRules = groupRules;
    }

    @Transactional
    public MonthlyReportResponse getByMonth(UUID monthId, UUID requesterId) {
        MonthJpaEntity month = months.findById(monthId)
                .orElseThrow(() -> new DomainException("Month not found"));
        EventJpaEntity event = events.findFirstByMonthIdAndTypeOrderByCreatedAtAsc(monthId, EventType.MONTHLY)
                .orElseGet(() -> createMonthlyEvent(month));
        groupRules.requireMembership(event.getGroup().getId(), requesterId);
        return buildReport(event, month);
    }

    @Transactional(readOnly = true)
    public MonthlyReportResponse getByEvent(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireMembership(event.getGroup().getId(), requesterId);
        MonthJpaEntity month = event.getMonth();
        return buildReport(event, month);
    }

    @Transactional(readOnly = true)
    public List<BalanceResult> calculateBalances(UUID eventId) {
        return calculateTotals(eventId).values().stream()
                .map(BalanceTotals::toResult)
                .toList();
    }

    private MonthlyReportResponse buildReport(EventJpaEntity event, MonthJpaEntity month) {
        Map<UUID, BalanceTotals> totalsByUser = calculateTotals(event.getId());
        BigDecimal totalExpenses = expenses.findByEventIdAndDeletedAtIsNull(event.getId()).stream()
                .map(ExpenseJpaEntity::getAmount)
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        List<MonthlyBalanceResponse> balances = totalsByUser.values().stream()
                .map(BalanceTotals::toResponse)
                .sorted(Comparator.comparing(MonthlyBalanceResponse::nickname))
                .toList();

        return new MonthlyReportResponse(
                month == null ? null : month.getId(),
                event.getId(),
                event.getName(),
                event.getGroup().getId(),
                event.getGroup().getName(),
                month == null ? 0 : month.getMonth(),
                month == null ? 0 : month.getYear(),
                event.getStatus().name(),
                totalExpenses,
                balances
        );
    }

    private Map<UUID, BalanceTotals> calculateTotals(UUID eventId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        Map<UUID, BalanceTotals> totalsByUser = new LinkedHashMap<>();
        groupMembers.findByGroupIdAndActiveTrue(event.getGroup().getId()).stream()
                .map(GroupMemberJpaEntity::getUser)
                .filter(user -> user.getDeletedAt() == null)
                .forEach(user -> totalsByUser.put(user.getId(), new BalanceTotals(user)));

        for (ExpenseJpaEntity expense : expenses.findByEventIdAndDeletedAtIsNull(eventId)) {
            expense.getParticipants().forEach(participant ->
                    totalsByUser.computeIfAbsent(participant.getUser().getId(), ignored -> new BalanceTotals(participant.getUser()))
                            .addConsumed(participant.getShareAmount()));

            expense.getPayers().forEach(payer ->
                    totalsByUser.computeIfAbsent(payer.getUser().getId(), ignored -> new BalanceTotals(payer.getUser()))
                            .addPaid(payer.getPaidAmount()));
        }
        return totalsByUser;
    }

    private EventJpaEntity createMonthlyEvent(MonthJpaEntity month) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(String.format("%02d/%d", month.getMonth(), month.getYear()));
        event.setDescription("Evento mensal criado automaticamente");
        event.setType(EventType.MONTHLY);
        event.setStatus(month.getStatus() == MonthStatus.CLOSED ? EventStatus.CLOSED : EventStatus.OPEN);
        event.setMonth(month);
        event.setGroup(defaultGroup());
        event.setCreatedAt(month.getCreatedAt());
        event.setClosedAt(month.getClosedAt());
        return events.save(event);
    }

    private GroupJpaEntity defaultGroup() {
        return groups.findFirstByActiveTrueOrderByCreatedAtAsc()
                .orElseThrow(() -> new DomainException("Group not found"));
    }

    public record BalanceResult(UUID userId, BigDecimal consumed, BigDecimal paid, BigDecimal balance) {
    }

    private static final class BalanceTotals {
        private final UserJpaEntity user;
        private BigDecimal consumed = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal paid = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        private BalanceTotals(UserJpaEntity user) {
            this.user = user;
        }

        private void addConsumed(BigDecimal amount) {
            consumed = consumed.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void addPaid(BigDecimal amount) {
            paid = paid.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private MonthlyBalanceResponse toResponse() {
            BigDecimal balance = paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return new MonthlyBalanceResponse(user.getId(), user.getNickname(), consumed, paid, balance);
        }

        private BalanceResult toResult() {
            BigDecimal balance = paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return new BalanceResult(user.getId(), consumed, paid, balance);
        }
    }
}
