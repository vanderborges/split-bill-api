package com.splitbill.application.usecase;

import com.splitbill.application.dto.CreateEventRequest;
import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.dto.StartSettlementRequest;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.MonthStatus;
import com.splitbill.domain.valueobject.SettlementStatus;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

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
    private final InstallmentRolloverService rollover;

    public EventUseCase(
            EventJpaRepository events,
            MonthJpaRepository months,
            ExpenseJpaRepository expenses,
            UserJpaRepository users,
            MonthlyReportUseCase reports,
            GroupJpaRepository groups,
            GroupMemberJpaRepository groupMembers,
            GroupUseCase groupRules,
            EventSettlementUseCase settlements,
            InstallmentRolloverService rollover
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
        this.rollover = rollover;
    }

    // Temporario so enxerga o evento ao qual esta amarrado.
    private boolean visibleTo(EventJpaEntity event, Map<UUID, GroupMemberJpaEntity> viewerMembership) {
        GroupMemberJpaEntity member = viewerMembership.get(event.getGroup().getId());
        return member == null || member.participatesIn(event.getId());
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
        // Participacao do viewer em cada grupo carregada uma vez so (antes
        // era uma consulta por evento da lista).
        Map<UUID, GroupMemberJpaEntity> viewerMembership = new HashMap<>();
        if (viewerUserId != null) {
            groupMembers.findByUserId(viewerUserId)
                    .forEach(member -> viewerMembership.put(member.getGroup().getId(), member));
        }
        return source.stream()
                .filter(event -> visibleTo(event, viewerMembership))
                .sorted(Comparator.comparing(EventJpaEntity::getCreatedAt))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EventResponse create(CreateEventRequest request, UUID requesterId) {
        groupRules.requireAdmin(request.groupId(), requesterId);
        GroupJpaEntity group = groups.findById(request.groupId())
                .orElseThrow(() -> new DomainException("Group not found"));
        if (request.type() != EventType.MONTHLY && (request.name() == null || request.name().isBlank())) {
            throw new DomainException("Informe o nome do evento");
        }
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
            if (events.findByMonthIdAndTypeAndGroupId(month.getId(), EventType.MONTHLY, group.getId()).isPresent()) {
                throw new DomainException("Ja existe um evento mensal "
                        + String.format("%02d/%d", month.getMonth(), month.getYear()) + " neste grupo");
            }
            requireNextMonthWhileOneIsOpen(group, month);
        }

        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        // Evento mensal se chama sempre pelo mes (MM/AAAA): o app nao pede
        // mais nome pra ele.
        event.setName(request.type() == EventType.MONTHLY
                ? String.format("%02d/%d", month.getMonth(), month.getYear())
                : request.name().trim());
        event.setDescription(request.description());
        event.setType(request.type());
        event.setStatus(EventStatus.OPEN);
        event.setMonth(month);
        event.setGroup(group);
        event.setCreatedAt(LocalDateTime.now());

        events.save(event);
        if (event.getType() == EventType.MONTHLY) {
            bringInstallmentsFromPreviousMonth(event);
        }
        return toResponse(event);
    }

    /**
     * Com um evento mensal ainda aberto no grupo, o proximo evento mensal
     * criado a mao tem que ser o mes seguinte ao ultimo ja existente, pra
     * manter a sequencia de meses (o front ja preenche e trava o campo).
     */
    private void requireNextMonthWhileOneIsOpen(GroupJpaEntity group, MonthJpaEntity month) {
        List<EventJpaEntity> monthlyEvents = monthlyEventsOf(group.getId());
        if (monthlyEvents.stream().noneMatch(event -> event.getStatus() == EventStatus.OPEN)) {
            return;
        }
        YearMonth expected = monthlyEvents.stream()
                .map(EventUseCase::referenceOf)
                .max(Comparator.naturalOrder())
                .orElseThrow()
                .plusMonths(1);
        if (!YearMonth.of(month.getYear(), month.getMonth()).equals(expected)) {
            throw new DomainException("Ja existe um mes aberto: o proximo evento mensal precisa ser "
                    + formatReference(expected));
        }
    }

    /**
     * Fechar um evento mensal so depois de fechar o mes anterior: nenhum
     * evento mensal do grupo de um mes antes deste pode continuar OPEN.
     */
    private void requirePreviousMonthsNotOpen(EventJpaEntity event) {
        YearMonth reference = referenceOf(event);
        monthlyEventsOf(event.getGroup().getId()).stream()
                .filter(other -> other.getStatus() == EventStatus.OPEN)
                .filter(other -> referenceOf(other).isBefore(reference))
                .map(EventUseCase::referenceOf)
                .min(Comparator.naturalOrder())
                .ifPresent(previous -> {
                    throw new DomainException("O mes anterior (" + formatReference(previous)
                            + ") ainda esta aberto. Feche ele antes de fechar este.");
                });
    }

    private List<EventJpaEntity> monthlyEventsOf(UUID groupId) {
        return events.findByGroupIdAndDeletedAtIsNull(groupId).stream()
                .filter(event -> event.getType() == EventType.MONTHLY)
                .filter(event -> event.getMonth() != null)
                .toList();
    }

    private static YearMonth referenceOf(EventJpaEntity event) {
        return YearMonth.of(event.getMonth().getYear(), event.getMonth().getMonth());
    }

    private static String formatReference(YearMonth reference) {
        return String.format("%02d/%d", reference.getMonthValue(), reference.getYear());
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
        if (event.getType() == EventType.MONTHLY && event.getMonth() != null) {
            requirePreviousMonthsNotOpen(event);
        }
        // App novo manda a escolha feita no "Fechar evento"; app antigo e o
        // fechamento automatico nao escolhem, e vale o recebedor do grupo
        // (comportamento de antes de 27/10/2026).
        boolean chosen = request != null && Boolean.TRUE.equals(request.receiverChosen());
        if (chosen) {
            event.setReceiver(request.receiverUserId() == null
                    ? null
                    : eventReceiver(event, request.receiverUserId()));
        } else {
            event.setReceiver(event.getGroup().getReceiver());
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
     * Quem recebe os pagamentos do evento: alguem com conta (pessoa sem
     * cadastro nao tem PIX nem abre o app) que participa deste evento.
     */
    private UserJpaEntity eventReceiver(EventJpaEntity event, UUID userId) {
        UserJpaEntity user = users.findById(userId)
                .filter(candidate -> candidate.isActive() && candidate.getDeletedAt() == null)
                .orElseThrow(() -> new DomainException("Pessoa nao encontrada"));
        if (user.isPlaceholder()) {
            throw new DomainException("Pessoa sem cadastro nao pode receber os pagamentos");
        }
        boolean participates = groupMembers.findByGroupIdAndUserId(event.getGroup().getId(), userId)
                .filter(GroupMemberJpaEntity::isActive)
                .map(member -> member.participatesIn(event.getId()))
                .orElse(false);
        if (!participates) {
            throw new DomainException("Quem recebe os pagamentos precisa participar deste evento");
        }
        return user;
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
                // Do mes mais antigo pro mais novo: cada um so fecha depois
                // do anterior (requirePreviousMonthsNotOpen).
                .sorted(Comparator.comparing(event -> event.getMonth() == null
                        ? YearMonth.of(9999, 12)
                        : referenceOf(event)))
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
                .anyMatch(settlement -> settlement.status() == SettlementStatus.PENDING
                        || settlement.status() == SettlementStatus.INFORMED);
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
        EventJpaEntity targetEvent = null;

        for (ExpenseJpaEntity eventExpense : eventExpenses) {
            if (!processedInstallmentGroups.add(eventExpense.getInstallmentGroup().getId())) {
                continue;
            }
            // Ja existe a proxima (em qualquer evento - inclusive a que ja
            // entrou no mes seguinte aberto) ou a serie acabou: nao duplica.
            Optional<Integer> nextInstallmentNumber = rollover.pendingNextNumber(eventExpense);
            if (nextInstallmentNumber.isEmpty()) {
                continue;
            }
            if (targetEvent == null) {
                targetEvent = resolveRolloverTarget(event);
            }
            rollover.createNext(eventExpense, targetEvent, nextInstallmentNumber.get());
        }
    }

    /**
     * Com o mes anterior ainda aberto, o evento mensal novo ja nasce com as
     * proximas parcelas/assinaturas dele - sem esperar o "Fechar evento" do
     * anterior (que depois so pula as que ja existem).
     */
    private void bringInstallmentsFromPreviousMonth(EventJpaEntity event) {
        rollover.previousMonthlyEvent(event)
                .filter(previous -> previous.getStatus() == EventStatus.OPEN)
                .ifPresent(previous -> expenses.findByEventIdAndDeletedAtIsNull(previous.getId()).stream()
                        .filter(expense -> expense.getInstallmentGroup() != null)
                        .forEach(rollover::propagateForward));
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
                event.getClosedAt(),
                event.getReceiver() == null ? null : event.getReceiver().getId(),
                event.getReceiver() == null ? null : event.getReceiver().getNickname()
        );
    }

    private record BalanceAmount(UUID userId, BigDecimal amount) {
        private BalanceAmount subtract(BigDecimal paid) {
            return new BalanceAmount(userId, amount.subtract(paid).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
    }
}
