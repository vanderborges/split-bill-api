package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateExpenseRequest;
import com.splitbill.application.dto.ExpensePayerRequest;
import com.splitbill.application.dto.ExpensePayerResponse;
import com.splitbill.application.dto.ExpenseParticipantResponse;
import com.splitbill.application.dto.ExpenseResponse;
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
import com.splitbill.infrastructure.persistence.entity.InstallmentGroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.MonthJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.InstallmentGroupJpaRepository;
import com.splitbill.infrastructure.persistence.repository.MonthJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ExpenseUseCase {

    private final ExpenseJpaRepository expenses;
    private final UserJpaRepository users;
    private final MonthJpaRepository months;
    private final EventJpaRepository events;
    private final InstallmentGroupJpaRepository installmentGroups;
    private final GroupJpaRepository groups;
    private final GroupMemberJpaRepository groupMembers;
    private final EntityManager entityManager;
    private final ExpenseSplitCalculator splitCalculator = new ExpenseSplitCalculator();

    public ExpenseUseCase(
            ExpenseJpaRepository expenses,
            UserJpaRepository users,
            MonthJpaRepository months,
            EventJpaRepository events,
            InstallmentGroupJpaRepository installmentGroups,
            GroupJpaRepository groups,
            GroupMemberJpaRepository groupMembers,
            EntityManager entityManager
    ) {
        this.expenses = expenses;
        this.users = users;
        this.months = months;
        this.events = events;
        this.installmentGroups = installmentGroups;
        this.groups = groups;
        this.groupMembers = groupMembers;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> listByMonth(UUID monthId) {
        return expenses.findByMonthIdAndDeletedAtIsNull(monthId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExpenseResponse> listByEvent(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        requireMembership(event.getGroup().getId(), requesterId);
        return expenses.findByEventIdAndDeletedAtIsNull(eventId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ExpenseResponse create(CreateExpenseRequest request, UUID requesterId) {
        validateInstallments(request);
        EventJpaEntity event = resolveEvent(request);
        requireMembership(event.getGroup().getId(), requesterId);
        MonthJpaEntity month = resolveMonth(request, event);
        validateOpen(event, month);

        if (request.installments() != null && request.installments() > 1) {
            validateMonthlyInstallmentEvent(event);
            return createInstallments(request, event, month);
        }

        LocalDateTime now = LocalDateTime.now();
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setMonth(month);
        expense.setEvent(event);
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);
        fillExpense(expense, request, request.description(), request.amount(), null, null);

        return toResponse(expenses.save(expense));
    }

    @Transactional
    public ExpenseResponse update(UUID id, CreateExpenseRequest request, UUID requesterId) {
        validateInstallments(request);
        ExpenseJpaEntity expense = expenses.findById(id)
                .orElseThrow(() -> new DomainException("Expense not found"));
        if (expense.getDeletedAt() != null) {
            throw new DomainException("Expense not found");
        }
        requireMembership(expense.getEvent().getGroup().getId(), requesterId);
        if (expense.getMonth() != null && expense.getMonth().getStatus() == MonthStatus.CLOSED) {
            throw new DomainException("Cannot edit expense from a closed month");
        }
        if (expense.getEvent().getStatus() == EventStatus.CLOSED) {
            throw new DomainException("Cannot edit expense from a closed event");
        }
        EventJpaEntity event = resolveEvent(request);
        requireMembership(event.getGroup().getId(), requesterId);
        MonthJpaEntity month = resolveMonth(request, event);
        validateOpen(event, month);
        expense.setEvent(event);
        expense.setMonth(month);
        fillExpense(expense, request, request.description(), request.amount(), expense.getInstallmentNumber(), expense.getTotalInstallments());
        expense.setUpdatedAt(LocalDateTime.now());
        return toResponse(expense);
    }

    @Transactional
    public void delete(UUID id, UUID adminUserId) {
        ExpenseJpaEntity expense = expenses.findById(id)
                .orElseThrow(() -> new DomainException("Expense not found"));
        if (!groupMembers.existsByGroupIdAndUserIdAndRoleAndActiveTrue(
                expense.getEvent().getGroup().getId(),
                adminUserId,
                com.splitbill.domain.valueobject.GroupMemberRole.ADMIN
        )) {
            throw new DomainException("Only group admins can delete expenses");
        }
        expense.setDeletedAt(LocalDateTime.now());
    }

    private ExpenseResponse createInstallments(CreateExpenseRequest request, EventJpaEntity firstEvent, MonthJpaEntity firstMonth) {
        if (firstMonth == null) {
            throw new DomainException("Installments must start from a monthly event or month");
        }
        List<ExpensePayerRequest> payerRequests = normalizePayers(request);
        if (payerRequests.size() != 1) {
            throw new DomainException("Installments currently support a single payer");
        }
        UserJpaEntity payer = users.findById(payerRequests.get(0).userId())
                .orElseThrow(() -> new DomainException("Payer not found"));

        LocalDateTime now = LocalDateTime.now();
        InstallmentGroupJpaEntity group = new InstallmentGroupJpaEntity();
        group.setId(UUID.randomUUID());
        group.setDescription(request.description());
        group.setTotalAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        group.setTotalInstallments(request.installments());
        group.setFirstEvent(firstEvent);
        group.setPayer(payer);
        group.setCategory(request.category());
        group.setCreatedAt(now);
        installmentGroups.save(group);

        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setMonth(firstMonth);
        expense.setEvent(firstEvent);
        expense.setInstallmentGroup(group);
        expense.setInstallmentNumber(1);
        expense.setTotalInstallments(request.installments());
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        String description = request.description() + " 1/" + request.installments();
        fillExpense(expense, request, description, request.amount(), 1, request.installments());
        return toResponse(expenses.save(expense));
    }

    private void validateMonthlyInstallmentEvent(EventJpaEntity event) {
        if (event.getType() != EventType.MONTHLY || event.getMonth() == null) {
            throw new DomainException("Parcelamento e permitido apenas para eventos mensais");
        }
    }

    private void validateInstallments(CreateExpenseRequest request) {
        if (request.installments() != null && request.installments() < 1) {
            throw new DomainException("Installments must be greater than zero");
        }
    }

    private void fillExpense(
            ExpenseJpaEntity expense,
            CreateExpenseRequest request,
            String description,
            BigDecimal amount,
            Integer installmentNumber,
            Integer totalInstallments
    ) {
        List<ExpensePayerRequest> payerRequests = normalizePayers(request);
        payerRequests = scalePayersForAmount(request.amount(), amount, payerRequests);
        validatePayersTotal(amount, payerRequests);

        Map<UUID, UserJpaEntity> participantsById = users.findAllById(request.participantIds()).stream()
                .collect(Collectors.toMap(UserJpaEntity::getId, Function.identity()));
        if (participantsById.size() != request.participantIds().stream().distinct().count()) {
            throw new DomainException("One or more participants were not found");
        }

        Map<UUID, UserJpaEntity> payersById = users.findAllById(payerRequests.stream()
                        .map(ExpensePayerRequest::userId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(UserJpaEntity::getId, Function.identity()));
        if (payersById.size() != payerRequests.stream().map(ExpensePayerRequest::userId).distinct().count()) {
            throw new DomainException("One or more payers were not found");
        }
        validateUsersBelongToEventGroup(expense.getEvent(), request.participantIds());
        validateUsersBelongToEventGroup(expense.getEvent(), payerRequests.stream()
                .map(ExpensePayerRequest::userId)
                .toList());

        UserJpaEntity mainPayer = payersById.get(payerRequests.get(0).userId());
        List<ParticipantShare> shares = splitCalculator.splitEqually(amount, request.participantIds());

        expense.setDescription(description);
        expense.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        expense.setExpenseDate(request.expenseDate());
        expense.setCategory(request.category());
        expense.setPayer(mainPayer);
        expense.setInstallmentNumber(installmentNumber);
        expense.setTotalInstallments(totalInstallments);
        clearCurrentShares(expense);

        for (ParticipantShare share : shares) {
            ExpenseParticipantJpaEntity participant = new ExpenseParticipantJpaEntity();
            participant.setId(UUID.randomUUID());
            participant.setExpense(expense);
            participant.setUser(participantsById.get(share.userId()));
            participant.setShareAmount(share.amount());
            expense.getParticipants().add(participant);
        }

        for (ExpensePayerRequest payerRequest : payerRequests) {
            ExpensePayerJpaEntity payer = new ExpensePayerJpaEntity();
            payer.setId(UUID.randomUUID());
            payer.setExpense(expense);
            payer.setUser(payersById.get(payerRequest.userId()));
            payer.setPaidAmount(payerRequest.amount().setScale(2, RoundingMode.HALF_UP));
            expense.getPayers().add(payer);
        }
    }

    private void validateUsersBelongToEventGroup(EventJpaEntity event, List<UUID> userIds) {
        for (UUID userId : userIds) {
            if (!groupMembers.existsByGroupIdAndUserIdAndActiveTrue(event.getGroup().getId(), userId)) {
                throw new DomainException("Expense users must belong to the event group");
            }
        }
    }

    private void requireMembership(UUID groupId, UUID userId) {
        if (!groupMembers.existsByGroupIdAndUserIdAndActiveTrue(groupId, userId)) {
            throw new DomainException("User does not belong to this group");
        }
    }

    private EventJpaEntity resolveEvent(CreateExpenseRequest request) {
        if (request.eventId() != null) {
            return events.findById(request.eventId())
                    .orElseThrow(() -> new DomainException("Event not found"));
        }
        if (request.monthId() == null) {
            throw new DomainException("Expense must have monthId or eventId");
        }
        MonthJpaEntity month = months.findById(request.monthId())
                .orElseThrow(() -> new DomainException("Month not found"));
        return findOrCreateMonthlyEvent(month, defaultGroup(), LocalDateTime.now());
    }

    private MonthJpaEntity resolveMonth(CreateExpenseRequest request, EventJpaEntity event) {
        if (request.monthId() != null) {
            return months.findById(request.monthId())
                    .orElseThrow(() -> new DomainException("Month not found"));
        }
        return event.getMonth();
    }

    private void validateOpen(EventJpaEntity event, MonthJpaEntity month) {
        if (event.getStatus() == EventStatus.CLOSED) {
            throw new DomainException("Cannot add expense to a closed event");
        }
        if (month != null && month.getStatus() == MonthStatus.CLOSED) {
            throw new DomainException("Cannot add expense to a closed month");
        }
    }

    private EventJpaEntity findOrCreateMonthlyEvent(MonthJpaEntity month, GroupJpaEntity group, LocalDateTime now) {
        return events.findByMonthIdAndTypeAndGroupId(month.getId(), EventType.MONTHLY, group.getId())
                .orElseGet(() -> {
                    EventJpaEntity event = new EventJpaEntity();
                    event.setId(UUID.randomUUID());
                    event.setName(String.format("%02d/%d", month.getMonth(), month.getYear()));
                    event.setDescription("Evento mensal criado automaticamente");
                    event.setType(EventType.MONTHLY);
                    event.setStatus(EventStatus.OPEN);
                    event.setMonth(month);
                    event.setGroup(group);
                    event.setCreatedAt(now);
                    return events.save(event);
                });
    }

    private GroupJpaEntity defaultGroup() {
        return groups.findFirstByActiveTrueOrderByCreatedAtAsc()
                .orElseThrow(() -> new DomainException("Group not found"));
    }

    private List<ExpensePayerRequest> scalePayersForAmount(
            BigDecimal originalAmount,
            BigDecimal targetAmount,
            List<ExpensePayerRequest> payerRequests
    ) {
        if (originalAmount.setScale(2, RoundingMode.HALF_UP)
                .compareTo(targetAmount.setScale(2, RoundingMode.HALF_UP)) == 0) {
            return payerRequests;
        }
        if (payerRequests.size() != 1) {
            throw new DomainException("Installments currently support a single payer");
        }
        return List.of(new ExpensePayerRequest(payerRequests.get(0).userId(), targetAmount));
    }

    private void clearCurrentShares(ExpenseJpaEntity expense) {
        if (expense.getParticipants().isEmpty() && expense.getPayers().isEmpty()) {
            return;
        }
        expense.getParticipants().clear();
        expense.getPayers().clear();
        entityManager.flush();
    }

    private List<ExpensePayerRequest> normalizePayers(CreateExpenseRequest request) {
        if (request.payers() != null && !request.payers().isEmpty()) {
            return request.payers();
        }
        if (request.payerId() == null) {
            throw new DomainException("Expense must have at least one payer");
        }
        return new ArrayList<>(List.of(new ExpensePayerRequest(request.payerId(), request.amount())));
    }

    private void validatePayersTotal(BigDecimal amount, List<ExpensePayerRequest> payers) {
        if (payers.isEmpty()) {
            throw new DomainException("Expense must have at least one payer");
        }
        if (payers.stream().map(ExpensePayerRequest::userId).distinct().count() != payers.size()) {
            throw new DomainException("Expense cannot have duplicated payers");
        }
        if (payers.stream().anyMatch(payer -> payer.amount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new DomainException("Payer amount must be greater than zero");
        }
        BigDecimal expected = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPaid = payers.stream()
                .map(payer -> payer.amount().setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
        if (expected.compareTo(totalPaid) != 0) {
            throw new DomainException("Total paid by payers must match expense amount");
        }
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
