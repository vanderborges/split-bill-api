package com.splitbill.application.usecase;

import com.splitbill.application.dto.CloseEventRequest;
import com.splitbill.application.dto.CreateEventRequest;
import com.splitbill.application.dto.EventResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.service.ExpenseSplitCalculator;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.MonthStatus;
import com.splitbill.domain.valueobject.ParticipantShare;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpensePayerJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseParticipantJpaEntity;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EventUseCase {

    private static final int MONEY_SCALE = 2;

    private final EventJpaRepository events;
    private final MonthJpaRepository months;
    private final ExpenseJpaRepository expenses;
    private final UserJpaRepository users;
    private final MonthlyReportUseCase reports;
    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository groupMembers;
    private final GroupUseCase groupRules;
    private final ExpenseSplitCalculator splitCalculator = new ExpenseSplitCalculator();

    public EventUseCase(
            EventJpaRepository events,
            MonthJpaRepository months,
            ExpenseJpaRepository expenses,
            UserJpaRepository users,
            MonthlyReportUseCase reports,
            GroupJpaRepository groups,
            GroupMemberJpaRepository groupMembers,
            GroupUseCase groupRules
    ) {
        this.events = events;
        this.months = months;
        this.expenses = expenses;
        this.users = users;
        this.reports = reports;
        this.groups = groups;
        this.groupMembers = groupMembers;
        this.groupRules = groupRules;
    }

    @Transactional(readOnly = true)
    public List<EventResponse> list(UUID viewerUserId, UUID groupId) {
        List<EventJpaEntity> source;
        if (groupId != null) {
            groupRules.requireMembership(groupId, viewerUserId);
            source = events.findByGroupIdAndDeletedAtIsNull(groupId);
        } else if (viewerUserId != null) {
            List<UUID> groupIds = groupMembers.findByUserIdAndActiveTrue(viewerUserId).stream()
                    .map(GroupMemberJpaEntity::getGroup)
                    .map(GroupJpaEntity::getId)
                    .toList();
            source = groupIds.isEmpty() ? List.of() : events.findByGroupIdInAndDeletedAtIsNull(groupIds);
        } else {
            source = events.findAll().stream()
                    .filter(event -> event.getDeletedAt() == null)
                    .toList();
        }
        return source.stream()
                .sorted(Comparator.comparing(EventJpaEntity::getCreatedAt))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        groupRules.requireAdmin(request.groupId(), request.adminUserId());
        GroupJpaEntity group = groups.findById(request.groupId())
                .orElseThrow(() -> new DomainException("Group not found"));
        MonthJpaEntity month = null;
        if (request.monthId() != null) {
            month = months.findById(request.monthId())
                    .orElseThrow(() -> new DomainException("Month not found"));
        } else if (request.type() == EventType.MONTHLY) {
            month = findOrCreateMonth(request.month(), request.year());
        }
        if (request.type() == EventType.MONTHLY && month == null) {
            throw new DomainException("Monthly event must have monthId or month/year");
        }
        if (request.type() == EventType.MONTHLY) {
            events.findByMonthIdAndTypeAndGroupId(month.getId(), EventType.MONTHLY, group.getId())
                    .ifPresent(event -> {
                        throw new DomainException("Monthly event already exists");
                    });
        }

        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(request.name());
        event.setDescription(request.description());
        event.setType(request.type());
        event.setStatus(EventStatus.OPEN);
        event.setMonth(month);
        event.setGroup(group);
        event.setCreatedAt(LocalDateTime.now());

        return toResponse(events.save(event));
    }

    private MonthJpaEntity findOrCreateMonth(Integer monthNumber, Integer yearNumber) {
        if (monthNumber == null || monthNumber < 1 || monthNumber > 12 || yearNumber == null || yearNumber < 2000) {
            throw new DomainException("Monthly event must have valid month and year");
        }
        return months.findByMonthAndYear(monthNumber, yearNumber)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now();
                    MonthJpaEntity month = new MonthJpaEntity();
                    month.setId(UUID.randomUUID());
                    month.setMonth(monthNumber);
                    month.setYear(yearNumber);
                    month.setStatus(MonthStatus.OPEN);
                    month.setOpenedAt(now);
                    month.setCreatedAt(now);
                    return months.save(month);
                });
    }

    @Transactional
    public EventResponse close(UUID id, CloseEventRequest request) {
        EventJpaEntity event = events.findById(id)
                .orElseThrow(() -> new DomainException("Event not found"));
        if (event.getStatus() == EventStatus.CLOSED) {
            throw new DomainException("Event is already closed");
        }

        if (request != null && request.consolidateToEventId() != null) {
            EventJpaEntity target = events.findById(request.consolidateToEventId())
                    .orElseThrow(() -> new DomainException("Target event not found"));
            if (target.getStatus() == EventStatus.CLOSED) {
                throw new DomainException("Cannot consolidate into a closed event");
            }
            if (target.getId().equals(event.getId())) {
                throw new DomainException("Cannot consolidate event into itself");
            }
            if (!target.getGroup().getId().equals(event.getGroup().getId())) {
                throw new DomainException("Cannot consolidate into an event from another group");
            }
            consolidateInto(event, target);
        }

        if (event.getType() == EventType.MONTHLY && event.getMonth() != null) {
            ensureFutureInstallments(event);
        }

        event.setStatus(EventStatus.CLOSED);
        event.setClosedAt(LocalDateTime.now());
        if (event.getType() == EventType.MONTHLY && event.getMonth() != null) {
            event.getMonth().setStatus(MonthStatus.CLOSED);
            event.getMonth().setClosedAt(event.getClosedAt());
        }
        return toResponse(event);
    }

    private void ensureFutureInstallments(EventJpaEntity event) {
        List<ExpenseJpaEntity> eventExpenses = expenses.findByEventIdAndDeletedAtIsNull(event.getId()).stream()
                .filter(expense -> expense.getInstallmentGroup() != null)
                .toList();
        Set<UUID> processedInstallmentGroups = new HashSet<>();

        for (ExpenseJpaEntity eventExpense : eventExpenses) {
            UUID installmentGroupId = eventExpense.getInstallmentGroup().getId();
            if (!processedInstallmentGroups.add(installmentGroupId)) {
                continue;
            }
            if (eventExpense.getInstallmentNumber() == null || eventExpense.getTotalInstallments() == null) {
                continue;
            }

            int nextInstallmentNumber = eventExpense.getInstallmentNumber() + 1;
            if (nextInstallmentNumber > eventExpense.getTotalInstallments()) {
                continue;
            }

            List<ExpenseJpaEntity> groupExpenses = expenses.findByInstallmentGroupIdAndDeletedAtIsNull(
                    installmentGroupId
            );
            Map<Integer, ExpenseJpaEntity> expensesByInstallment = groupExpenses.stream()
                    .filter(expense -> expense.getInstallmentNumber() != null)
                    .collect(Collectors.toMap(ExpenseJpaEntity::getInstallmentNumber, Function.identity(), (first, ignored) -> first));
            if (expensesByInstallment.containsKey(nextInstallmentNumber)) {
                continue;
            }

            ExpenseJpaEntity template = expensesByInstallment.getOrDefault(eventExpense.getInstallmentNumber(), eventExpense);
            List<UUID> participantIds = template.getParticipants().stream()
                    .map(participant -> participant.getUser().getId())
                    .toList();
            YearMonth nextReference = YearMonth.of(event.getMonth().getYear(), event.getMonth().getMonth()).plusMonths(1);
            MonthJpaEntity month = findOrCreateMonth(nextReference.getMonthValue(), nextReference.getYear());
            EventJpaEntity targetEvent = ensureMonthlyEvent(month, event.getGroup());
            if (targetEvent.getStatus() == EventStatus.CLOSED) {
                throw new DomainException("Cannot create next installment in a closed monthly event");
            }
            createInstallmentExpense(
                    template,
                    targetEvent,
                    month,
                    participantIds,
                    template.getInstallmentGroup().getTotalAmount(),
                    nextInstallmentNumber
            );
        }
    }

    private void createInstallmentExpense(
            ExpenseJpaEntity template,
            EventJpaEntity targetEvent,
            MonthJpaEntity month,
            List<UUID> participantIds,
            BigDecimal amount,
            int installmentNumber
    ) {
        LocalDateTime now = LocalDateTime.now();
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setDescription(template.getInstallmentGroup().getDescription() + " " + installmentNumber + "/" + template.getTotalInstallments());
        expense.setAmount(amount);
        expense.setExpenseDate(nextExpenseDate(template, month));
        expense.setCategory(template.getCategory());
        expense.setPayer(template.getInstallmentGroup().getPayer());
        expense.setMonth(month);
        expense.setEvent(targetEvent);
        expense.setInstallmentGroup(template.getInstallmentGroup());
        expense.setInstallmentNumber(installmentNumber);
        expense.setTotalInstallments(template.getTotalInstallments());
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        for (ParticipantShare share : splitCalculator.splitEqually(amount, participantIds)) {
            ExpenseParticipantJpaEntity participant = new ExpenseParticipantJpaEntity();
            participant.setId(UUID.randomUUID());
            participant.setExpense(expense);
            participant.setUser(template.getParticipants().stream()
                    .filter(item -> item.getUser().getId().equals(share.userId()))
                    .findFirst()
                    .orElseThrow(() -> new DomainException("Installment participant not found"))
                    .getUser());
            participant.setShareAmount(share.amount());
            expense.getParticipants().add(participant);
        }

        ExpensePayerJpaEntity payer = new ExpensePayerJpaEntity();
        payer.setId(UUID.randomUUID());
        payer.setExpense(expense);
        payer.setUser(template.getInstallmentGroup().getPayer());
        payer.setPaidAmount(amount);
        expense.getPayers().add(payer);

        expenses.save(expense);
    }

    private LocalDate nextExpenseDate(ExpenseJpaEntity template, MonthJpaEntity month) {
        int day = Math.min(template.getExpenseDate().getDayOfMonth(), YearMonth.of(month.getYear(), month.getMonth()).lengthOfMonth());
        return LocalDate.of(month.getYear(), month.getMonth(), day);
    }

    @Transactional
    public EventResponse reopen(UUID id) {
        EventJpaEntity event = events.findById(id)
                .orElseThrow(() -> new DomainException("Event not found"));
        event.setStatus(EventStatus.OPEN);
        event.setClosedAt(null);
        if (event.getType() == EventType.MONTHLY && event.getMonth() != null) {
            event.getMonth().setStatus(MonthStatus.OPEN);
            event.getMonth().setClosedAt(null);
        }
        return toResponse(event);
    }

    @Transactional
    public void delete(UUID id, UUID adminUserId) {
        EventJpaEntity event = events.findById(id)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireAdmin(event.getGroup().getId(), adminUserId);
        event.setDeletedAt(LocalDateTime.now());
    }

    @Transactional
    public EventJpaEntity ensureMonthlyEvent(MonthJpaEntity month, GroupJpaEntity group) {
        return events.findByMonthIdAndTypeAndGroupId(month.getId(), EventType.MONTHLY, group.getId())
                .orElseGet(() -> {
                    EventJpaEntity event = new EventJpaEntity();
                    event.setId(UUID.randomUUID());
                    event.setName(String.format("%02d/%d", month.getMonth(), month.getYear()));
                    event.setDescription("Evento mensal criado automaticamente");
                    event.setType(EventType.MONTHLY);
                    event.setStatus(month.getStatus() == MonthStatus.CLOSED ? EventStatus.CLOSED : EventStatus.OPEN);
                    event.setMonth(month);
                    event.setGroup(group);
                    event.setCreatedAt(month.getCreatedAt());
                    event.setClosedAt(month.getClosedAt());
                    return events.save(event);
                });
    }

    private void consolidateInto(EventJpaEntity source, EventJpaEntity target) {
        List<MonthlyReportUseCase.BalanceResult> balances = reports.calculateBalances(source.getId());
        Queue<BalanceAmount> creditors = new ArrayDeque<>(balances.stream()
                .filter(balance -> balance.balance().compareTo(BigDecimal.ZERO) > 0)
                .map(balance -> new BalanceAmount(balance.userId(), balance.balance()))
                .toList());

        for (MonthlyReportUseCase.BalanceResult debtor : balances.stream()
                .filter(balance -> balance.balance().compareTo(BigDecimal.ZERO) < 0)
                .toList()) {
            BigDecimal remainingDebt = debtor.balance().abs().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            UserJpaEntity debtorUser = users.findById(debtor.userId())
                    .orElseThrow(() -> new DomainException("Debtor user not found"));

            while (remainingDebt.compareTo(BigDecimal.ZERO) > 0 && !creditors.isEmpty()) {
                BalanceAmount creditor = creditors.peek();
                BigDecimal amount = remainingDebt.min(creditor.amount()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                UserJpaEntity creditorUser = users.findById(creditor.userId())
                        .orElseThrow(() -> new DomainException("Creditor user not found"));

                createConsolidationExpense(source, target, creditorUser, debtorUser, amount);

                remainingDebt = remainingDebt.subtract(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                creditor = creditor.subtract(amount);
                creditors.poll();
                if (creditor.amount().compareTo(BigDecimal.ZERO) > 0) {
                    creditors.add(creditor);
                }
            }
        }
    }

    private void createConsolidationExpense(
            EventJpaEntity source,
            EventJpaEntity target,
            UserJpaEntity creditor,
            UserJpaEntity debtor,
            BigDecimal amount
    ) {
        LocalDateTime now = LocalDateTime.now();
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setDescription("Acerto " + source.getName() + " - " + debtor.getNickname());
        expense.setAmount(amount);
        expense.setExpenseDate(LocalDate.now());
        expense.setCategory("Acerto");
        expense.setPayer(creditor);
        expense.setMonth(target.getMonth() == null ? source.getMonth() : target.getMonth());
        expense.setEvent(target);
        expense.setSourceEvent(source);
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        ExpenseParticipantJpaEntity participant = new ExpenseParticipantJpaEntity();
        participant.setId(UUID.randomUUID());
        participant.setExpense(expense);
        participant.setUser(debtor);
        participant.setShareAmount(amount);
        expense.getParticipants().add(participant);

        ExpensePayerJpaEntity payer = new ExpensePayerJpaEntity();
        payer.setId(UUID.randomUUID());
        payer.setExpense(expense);
        payer.setUser(creditor);
        payer.setPaidAmount(amount);
        expense.getPayers().add(payer);

        expenses.save(expense);
    }

    private EventResponse toResponse(EventJpaEntity event) {
        MonthJpaEntity month = event.getMonth();
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getType(),
                event.getStatus(),
                event.getGroup().getId(),
                month == null ? null : month.getId(),
                month == null ? null : month.getMonth(),
                month == null ? null : month.getYear(),
                event.getCreatedAt(),
                event.getClosedAt()
        );
    }

    private record BalanceAmount(UUID userId, BigDecimal amount) {
        private BalanceAmount subtract(BigDecimal paid) {
            return new BalanceAmount(userId, amount.subtract(paid).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
    }
}
