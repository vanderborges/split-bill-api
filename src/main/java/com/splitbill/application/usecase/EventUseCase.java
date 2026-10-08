package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateEventRequest;
import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.dto.StartSettlementRequest;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.service.ExpenseSplitCalculator;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.MonthStatus;
import com.splitbill.domain.valueobject.SettlementStatus;
import com.splitbill.domain.valueobject.ParticipantShare;
import com.splitbill.domain.valueobject.ParticipantSplit;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpensePayerJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseParticipantJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.InstallmentGroupJpaEntity;
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
import java.util.Optional;
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
    private final EventSettlementUseCase settlements;
    private final ExpenseSplitCalculator splitCalculator = new ExpenseSplitCalculator();

    public EventUseCase(
            EventJpaRepository events,
            MonthJpaRepository months,
            ExpenseJpaRepository expenses,
            UserJpaRepository users,
            MonthlyReportUseCase reports,
            GroupJpaRepository groups,
            GroupMemberJpaRepository groupMembers,
            GroupUseCase groupRules,
            EventSettlementUseCase settlements
    ) {
        this.events = events;
        this.months = months;
        this.expenses = expenses;
        this.users = users;
        this.reports = reports;
        this.groups = groups;
        this.groupMembers = groupMembers;
        this.groupRules = groupRules;
        this.settlements = settlements;
    }

    // Temporario so enxerga o evento ao qual esta amarrado.
    private boolean visibleTo(EventJpaEntity event, UUID viewerUserId) {
        if (viewerUserId == null) {
            return true;
        }
        return groupMembers.findByGroupIdAndUserId(event.getGroup().getId(), viewerUserId)
                .map(member -> member.participatesIn(event.getId()))
                .orElse(true);
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
                .filter(event -> visibleTo(event, viewerUserId))
                .sorted(Comparator.comparing(EventJpaEntity::getCreatedAt))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EventResponse create(CreateEventRequest request, UUID requesterId) {
        groupRules.requireAdmin(request.groupId(), requesterId);
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

    /**
     * Primeira etapa do fechamento: congela o evento para novas despesas e
     * dispara os efeitos que dependem da lista final de despesas (rolar a
     * próxima parcela mensal, consolidar saldo em outro evento). A partir
     * daqui o evento fica em {@link EventStatus#SETTLING}, aguardando todo
     * mundo confirmar pagamento antes do fechamento definitivo.
     */
    @Transactional
    public EventResponse startSettlement(UUID id, StartSettlementRequest request, UUID requesterId) {
        EventJpaEntity event = events.findById(id)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireAdmin(event.getGroup().getId(), requesterId);
        startSettlementCore(event, request);
        return toResponse(event);
    }

    private void startSettlementCore(EventJpaEntity event, StartSettlementRequest request) {
        if (event.getStatus() != EventStatus.OPEN) {
            throw new DomainException("Only an open event can start settlement");
        }

        if (request != null && request.consolidateToEventId() != null) {
            EventJpaEntity target = events.findById(request.consolidateToEventId())
                    .orElseThrow(() -> new DomainException("Target event not found"));
            if (target.getStatus() != EventStatus.OPEN) {
                throw new DomainException("Cannot consolidate into an event that is not open");
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

        event.setStatus(EventStatus.SETTLING);
    }

    /**
     * Usado pelo {@link com.splitbill.infrastructure.scheduling.GroupAutoSettlementScheduler}:
     * abre pra pagamento todos os eventos mensais ainda OPEN do grupo, sem
     * exigir um admin fazendo a requisição (é o próprio sistema, no dia
     * configurado em {@link GroupUseCase#setAutoSettlementDay}). Retorna os
     * eventos que de fato mudaram de status, pra quem chamou saber em quais
     * disparar o alerta de cobrança.
     */
    @Transactional
    public List<EventJpaEntity> autoStartSettlementForGroup(GroupJpaEntity group) {
        List<EventJpaEntity> openMonthlyEvents = events.findByGroupIdAndDeletedAtIsNull(group.getId()).stream()
                .filter(event -> event.getType() == EventType.MONTHLY)
                .filter(event -> event.getStatus() == EventStatus.OPEN)
                .toList();
        for (EventJpaEntity event : openMonthlyEvents) {
            startSettlementCore(event, null);
        }
        return openMonthlyEvents;
    }

    /**
     * Fechamento definitivo: só é permitido depois que o evento passou por
     * {@link #startSettlement} e todos os acertos (pagamentos) já foram
     * confirmados - nada de pendência em aberto na tela de acertos.
     */
    @Transactional
    public EventResponse close(UUID id, UUID requesterId) {
        EventJpaEntity event = events.findById(id)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireAdmin(event.getGroup().getId(), requesterId);
        if (event.getStatus() == EventStatus.CLOSED) {
            throw new DomainException("Event is already closed");
        }
        if (event.getStatus() != EventStatus.SETTLING) {
            throw new DomainException("Abra o evento para pagamento antes de fechar");
        }
        boolean hasPendingPayment = settlements.listByEvent(id, requesterId).stream()
                .anyMatch(settlement -> settlement.status() == SettlementStatus.PENDING);
        if (hasPendingPayment) {
            throw new DomainException(
                    "Existem pagamentos pendentes. Confirme todos os pagamentos antes de fechar o evento.");
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
            InstallmentGroupJpaEntity installmentGroup = eventExpense.getInstallmentGroup();
            UUID installmentGroupId = installmentGroup.getId();
            if (!processedInstallmentGroups.add(installmentGroupId)) {
                continue;
            }
            if (eventExpense.getInstallmentNumber() == null) {
                continue;
            }
            int nextInstallmentNumber = eventExpense.getInstallmentNumber() + 1;

            // Assinatura: sem numero fixo de parcelas, so para quando alguem
            // cancela (ver ExpenseUseCase#cancelSubscription). Parcelamento
            // comum: para quando atinge o total contratado.
            if (installmentGroup.isSubscription()) {
                if (installmentGroup.getCancelledAt() != null) {
                    continue;
                }
            } else if (eventExpense.getTotalInstallments() == null
                    || nextInstallmentNumber > eventExpense.getTotalInstallments()) {
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
            List<ParticipantSplit> participants = template.getParticipants().stream()
                    .map(participant -> new ParticipantSplit(
                            participant.getUser().getId(),
                            participant.getShareCount(),
                            participant.getShareDescription()
                    ))
                    .toList();
            EventJpaEntity targetEvent = resolveRolloverTarget(event);
            if (targetEvent.getStatus() == EventStatus.CLOSED) {
                throw new DomainException("Cannot create next installment in a closed monthly event");
            }
            // Mantem a data cadastrada originalmente (a da 1a parcela) em vez de
            // mover pro mes do evento de destino - assim a parcela que tombou
            // fica no fim da lista (ordenada da mais recente pra mais antiga).
            LocalDate originalDate = expensesByInstallment.getOrDefault(1, template).getExpenseDate();
            createInstallmentExpense(
                    template,
                    targetEvent,
                    targetEvent.getMonth(),
                    participants,
                    template.getInstallmentGroup().getTotalAmount(),
                    nextInstallmentNumber,
                    originalDate
            );
        }
    }

    /**
     * Decide pra qual evento mensal rolar a próxima parcela/assinatura. Se
     * o grupo já tem outro evento mensal aberto (não fechado) num mês
     * depois do evento atual, usa o mais próximo desses em vez de pular
     * direto pro mês seguinte - sem isso, fechar um evento mais antigo
     * (ex.: 09) podia criar um evento novo lá na frente (ex.: 11) mesmo
     * já existindo um evento mais próximo ainda aberto (ex.: 10), porque
     * o cálculo original sempre usava "mês do evento atual + 1" sem
     * checar se já havia algo além disso.
     */
    private EventJpaEntity resolveRolloverTarget(EventJpaEntity sourceEvent) {
        YearMonth sourceReference = YearMonth.of(
                sourceEvent.getMonth().getYear(), sourceEvent.getMonth().getMonth());

        Optional<EventJpaEntity> existingLater = events
                .findByGroupIdAndDeletedAtIsNull(sourceEvent.getGroup().getId()).stream()
                .filter(candidate -> candidate.getType() == EventType.MONTHLY)
                .filter(candidate -> candidate.getMonth() != null)
                .filter(candidate -> candidate.getStatus() != EventStatus.CLOSED)
                .filter(candidate -> !candidate.getId().equals(sourceEvent.getId()))
                .filter(candidate -> YearMonth.of(candidate.getMonth().getYear(), candidate.getMonth().getMonth())
                        .isAfter(sourceReference))
                .min(Comparator.comparing(candidate ->
                        YearMonth.of(candidate.getMonth().getYear(), candidate.getMonth().getMonth())));
        if (existingLater.isPresent()) {
            return existingLater.get();
        }

        YearMonth nextReference = sourceReference.plusMonths(1);
        MonthJpaEntity month = findOrCreateMonth(nextReference.getMonthValue(), nextReference.getYear());
        return ensureMonthlyEvent(month, sourceEvent.getGroup());
    }

    private void createInstallmentExpense(
            ExpenseJpaEntity template,
            EventJpaEntity targetEvent,
            MonthJpaEntity month,
            List<ParticipantSplit> participants,
            BigDecimal amount,
            int installmentNumber,
            LocalDate expenseDate
    ) {
        LocalDateTime now = LocalDateTime.now();
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setDescription(template.getInstallmentGroup().getDescription());
        expense.setAmount(amount);
        expense.setExpenseDate(expenseDate);
        expense.setCategory(template.getCategory());
        expense.setPayer(template.getPayer());
        expense.setCreatedBy(template.getCreatedBy());
        expense.setMonth(month);
        expense.setEvent(targetEvent);
        expense.setInstallmentGroup(template.getInstallmentGroup());
        expense.setInstallmentNumber(installmentNumber);
        expense.setTotalInstallments(template.getTotalInstallments());
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        for (ParticipantShare share : splitCalculator.splitByShares(amount, participants)) {
            ExpenseParticipantJpaEntity participant = new ExpenseParticipantJpaEntity();
            participant.setId(UUID.randomUUID());
            participant.setExpense(expense);
            participant.setUser(template.getParticipants().stream()
                    .filter(item -> item.getUser().getId().equals(share.userId()))
                    .findFirst()
                    .orElseThrow(() -> new DomainException("Installment participant not found"))
                    .getUser());
            participant.setShareAmount(share.amount());
            participant.setShareCount(share.shareCount());
            participant.setShareDescription(share.shareDescription());
            expense.getParticipants().add(participant);
        }

        ExpensePayerJpaEntity payer = new ExpensePayerJpaEntity();
        payer.setId(UUID.randomUUID());
        payer.setExpense(expense);
        payer.setUser(template.getPayer());
        payer.setPaidAmount(amount);
        expense.getPayers().add(payer);

        expenses.save(expense);
    }

    @Transactional
    public EventResponse reopen(UUID id, UUID requesterId) {
        EventJpaEntity event = events.findById(id)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireAdmin(event.getGroup().getId(), requesterId);
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
        LocalDateTime now = LocalDateTime.now();
        event.setDeletedAt(now);
        // Apaga junto as despesas do evento - sem isso elas continuavam
        // aparecendo no Extrato (e em qualquer consulta que so filtra
        // expense.deletedAt), mesmo com o evento ja apagado.
        expenses.findByEventIdAndDeletedAtIsNull(event.getId())
                .forEach(expense -> expense.setDeletedAt(now));
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
                    // O status do evento e dessa pessoa/grupo so - nao do
                    // Month compartilhado (ver nota em validateOpen de
                    // ExpenseUseCase): outro grupo ja ter fechado o mes nao
                    // pode nascer esse evento ja fechado.
                    event.setStatus(EventStatus.OPEN);
                    event.setMonth(month);
                    event.setGroup(group);
                    event.setCreatedAt(month.getCreatedAt());
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
        expense.setCreatedBy(creditor);
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
        participant.setShareCount(1);
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
