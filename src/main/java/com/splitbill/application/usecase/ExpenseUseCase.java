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
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.domain.valueobject.MonthStatus;
import com.splitbill.domain.valueobject.ParticipantShare;
import com.splitbill.domain.valueobject.ParticipantSplit;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final InstallmentRolloverService rollover;
    private final ExpenseSplitCalculator splitCalculator = new ExpenseSplitCalculator();

    public ExpenseUseCase(
            ExpenseJpaRepository expenses,
            UserJpaRepository users,
            MonthJpaRepository months,
            EventJpaRepository events,
            InstallmentGroupJpaRepository installmentGroups,
            GroupJpaRepository groups,
            GroupMemberJpaRepository groupMembers,
            EntityManager entityManager,
            InstallmentRolloverService rollover
    ) {
        this.expenses = expenses;
        this.users = users;
        this.months = months;
        this.events = events;
        this.installmentGroups = installmentGroups;
        this.groups = groups;
        this.groupMembers = groupMembers;
        this.entityManager = entityManager;
        this.rollover = rollover;
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
                .orElseThrow(() -> new DomainException("Evento não encontrado."));
        requireEventAccess(event, requesterId);
        return expenses.findByEventIdAndDeletedAtIsNull(eventId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ExpenseResponse create(CreateExpenseRequest request, UUID requesterId) {
        validateInstallments(request);
        EventJpaEntity event = resolveEvent(request);
        requireEventAccess(event, requesterId);
        MonthJpaEntity month = resolveMonth(request, event);
        validateOpen(event);

        if (Boolean.TRUE.equals(request.subscription())) {
            validateMonthlyInstallmentEvent(event);
            return createSubscription(request, event, month, requesterId);
        }

        if (request.installments() != null && request.installments() > 1) {
            validateMonthlyInstallmentEvent(event);
            return createInstallments(request, event, month, requesterId);
        }

        LocalDateTime now = LocalDateTime.now();
        UserJpaEntity creator = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setMonth(month);
        expense.setEvent(event);
        expense.setCreatedBy(creator);
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);
        fillExpense(expense, request, request.description(), request.amount(), null, null);

        return toResponse(expenses.save(expense));
    }

    @Transactional
    public ExpenseResponse update(UUID id, CreateExpenseRequest request, UUID requesterId) {
        validateInstallments(request);
        ExpenseJpaEntity expense = expenses.findById(id)
                .orElseThrow(() -> new DomainException("Despesa não encontrada."));
        if (expense.getDeletedAt() != null) {
            throw new DomainException("Despesa não encontrada.");
        }
        requireEventAccess(expense.getEvent(), requesterId);
        if (expense.getEvent().getStatus() != EventStatus.OPEN) {
            throw new DomainException("Não é possível editar despesa de um evento que não está aberto.");
        }
        requireExpenseChangePermission(expense, requesterId);
        EventJpaEntity event = resolveEvent(request);
        requireEventAccess(event, requesterId);
        MonthJpaEntity month = resolveMonth(request, event);
        validateOpen(event);
        expense.setEvent(event);
        expense.setMonth(month);
        fillExpense(expense, request, request.description(), request.amount(), expense.getInstallmentNumber(), expense.getTotalInstallments());
        expense.setUpdatedAt(LocalDateTime.now());
        if (expense.getInstallmentGroup() != null) {
            // Parcelas seguintes ja colocadas nos meses abertos sao refeitas
            // com os dados novos (como seria no "Fechar evento").
            rollover.discardForward(expense);
            rollover.propagateForward(expense);
        }
        return toResponse(expense);
    }

    @Transactional
    public void delete(UUID id, UUID requesterId) {
        ExpenseJpaEntity expense = expenses.findById(id)
                .orElseThrow(() -> new DomainException("Despesa não encontrada."));
        if (expense.getDeletedAt() != null) {
            throw new DomainException("Despesa não encontrada.");
        }
        if (expense.getEvent().getStatus() != EventStatus.OPEN) {
            throw new DomainException("Não é possível excluir despesa de um evento que não está aberto.");
        }
        requireExpenseChangePermission(expense, requesterId);
        expense.setDeletedAt(LocalDateTime.now());
        // Apagar uma parcela encerra a serie dali pra frente: tira tambem as
        // seguintes que ja estavam nos meses abertos.
        rollover.discardForward(expense);
    }

    /**
     * Finaliza uma assinatura: admin ou quem cadastrou pode encerrar a
     * partir do mes corrente. A despesa deste mes continua existindo -
     * so para de gerar a proxima, no {@link EventUseCase#startSettlement}
     * do evento atual (ver EventUseCase#ensureFutureInstallments).
     */
    @Transactional
    public ExpenseResponse cancelSubscription(UUID id, UUID requesterId) {
        ExpenseJpaEntity expense = expenses.findById(id)
                .orElseThrow(() -> new DomainException("Despesa não encontrada."));
        if (expense.getDeletedAt() != null) {
            throw new DomainException("Despesa não encontrada.");
        }
        requireExpenseChangePermission(expense, requesterId);
        InstallmentGroupJpaEntity group = expense.getInstallmentGroup();
        if (group == null || !group.isSubscription()) {
            throw new DomainException("Esta despesa não é uma assinatura.");
        }
        if (group.getCancelledAt() == null) {
            group.setCancelledAt(LocalDateTime.now());
            // A deste mes fica; a(s) que ja tinham entrado no(s) mes(es)
            // seguinte(s) aberto(s) saem.
            rollover.discardForward(expense);
        }
        return toResponse(expense);
    }

    private ExpenseResponse createInstallments(
            CreateExpenseRequest request,
            EventJpaEntity firstEvent,
            MonthJpaEntity firstMonth,
            UUID requesterId
    ) {
        if (firstMonth == null) {
            throw new DomainException("O parcelamento precisa começar em um evento mensal.");
        }
        List<ExpensePayerRequest> payerRequests = normalizePayers(request);
        if (payerRequests.size() != 1) {
            throw new DomainException("O parcelamento permite apenas um pagador.");
        }
        UserJpaEntity payer = users.findById(payerRequests.get(0).userId())
                .orElseThrow(() -> new DomainException("Pagador não encontrado."));
        UserJpaEntity creator = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));

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
        expense.setCreatedBy(creator);
        expense.setInstallmentGroup(group);
        expense.setInstallmentNumber(1);
        expense.setTotalInstallments(request.installments());
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        fillExpense(expense, request, request.description(), request.amount(), 1, request.installments());
        ExpenseJpaEntity saved = expenses.save(expense);
        rollover.propagateForward(saved);
        return toResponse(saved);
    }

    /**
     * Assinatura: igual a uma despesa parcelada, mas sem numero fixo de
     * parcelas - repete todo mes (via
     * {@link EventUseCase#ensureFutureInstallments}) ate alguem
     * finalizar com {@link #cancelSubscription}.
     */
    private ExpenseResponse createSubscription(
            CreateExpenseRequest request,
            EventJpaEntity firstEvent,
            MonthJpaEntity firstMonth,
            UUID requesterId
    ) {
        if (firstMonth == null) {
            throw new DomainException("A assinatura deve começar a partir de um evento mensal.");
        }
        List<ExpensePayerRequest> payerRequests = normalizePayers(request);
        if (payerRequests.size() != 1) {
            throw new DomainException("A assinatura permite apenas um pagador.");
        }
        UserJpaEntity payer = users.findById(payerRequests.get(0).userId())
                .orElseThrow(() -> new DomainException("Pagador não encontrado."));
        UserJpaEntity creator = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));

        LocalDateTime now = LocalDateTime.now();
        InstallmentGroupJpaEntity group = new InstallmentGroupJpaEntity();
        group.setId(UUID.randomUUID());
        group.setDescription(request.description());
        group.setTotalAmount(request.amount().setScale(2, RoundingMode.HALF_UP));
        group.setTotalInstallments(null);
        group.setSubscription(true);
        group.setFirstEvent(firstEvent);
        group.setPayer(payer);
        group.setCategory(request.category());
        group.setCreatedAt(now);
        installmentGroups.save(group);

        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setMonth(firstMonth);
        expense.setEvent(firstEvent);
        expense.setCreatedBy(creator);
        expense.setInstallmentGroup(group);
        expense.setInstallmentNumber(1);
        expense.setTotalInstallments(null);
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        fillExpense(expense, request, request.description(), request.amount(), 1, null);
        ExpenseJpaEntity saved = expenses.save(expense);
        rollover.propagateForward(saved);
        return toResponse(saved);
    }

    private void validateMonthlyInstallmentEvent(EventJpaEntity event) {
        if (event.getType() != EventType.MONTHLY || event.getMonth() == null) {
            throw new DomainException("Parcelamento é permitido apenas para eventos mensais.");
        }
    }

    private void validateInstallments(CreateExpenseRequest request) {
        if (request.installments() != null && request.installments() < 1) {
            throw new DomainException("O número de parcelas precisa ser maior que zero.");
        }
        if (Boolean.TRUE.equals(request.subscription())
                && request.installments() != null && request.installments() > 1) {
            throw new DomainException("Uma despesa não pode ser assinatura e parcelada ao mesmo tempo.");
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

        List<ParticipantSplit> participantRequests = normalizeParticipants(request);
        List<UUID> participantIds = participantRequests.stream()
                .map(ParticipantSplit::userId)
                .toList();
        Map<UUID, UserJpaEntity> participantsById = users.findAllById(participantIds).stream()
                .collect(Collectors.toMap(UserJpaEntity::getId, Function.identity()));
        if (participantsById.size() != participantIds.stream().distinct().count()) {
            throw new DomainException("Um ou mais participantes não foram encontrados.");
        }

        Map<UUID, UserJpaEntity> payersById = users.findAllById(payerRequests.stream()
                        .map(ExpensePayerRequest::userId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(UserJpaEntity::getId, Function.identity()));
        if (payersById.size() != payerRequests.stream().map(ExpensePayerRequest::userId).distinct().count()) {
            throw new DomainException("Um ou mais pagadores não foram encontrados.");
        }
        // Quem ja estava nesta despesa pode continuar nela mesmo removido do
        // grupo: a pessoa so some de vez quando o evento acaba.
        Set<UUID> alreadyInExpense = new HashSet<>();
        expense.getParticipants().forEach(participant -> alreadyInExpense.add(participant.getUser().getId()));
        expense.getPayers().forEach(payer -> alreadyInExpense.add(payer.getUser().getId()));
        validateUsersBelongToEventGroup(expense.getEvent(), participantIds, alreadyInExpense);
        validateUsersBelongToEventGroup(expense.getEvent(), payerRequests.stream()
                .map(ExpensePayerRequest::userId)
                .toList(), alreadyInExpense);

        UserJpaEntity mainPayer = payersById.get(payerRequests.get(0).userId());
        List<ParticipantShare> shares = splitCalculator.splitByShares(amount, participantRequests);

        expense.setDescription(description);
        expense.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        expense.setExpenseDate(request.expenseDate());
        expense.setCategory(request.category());
        expense.setPayer(mainPayer);
        expense.setInstallmentNumber(installmentNumber);
        expense.setTotalInstallments(totalInstallments);
        expense.setCustomSplit(participantRequests.stream().anyMatch(participant -> participant.fixedAmount() != null));
        clearCurrentShares(expense);

        for (ParticipantShare share : shares) {
            ExpenseParticipantJpaEntity participant = new ExpenseParticipantJpaEntity();
            participant.setId(UUID.randomUUID());
            participant.setExpense(expense);
            participant.setUser(participantsById.get(share.userId()));
            participant.setShareAmount(share.amount());
            participant.setShareCount(share.shareCount());
            participant.setShareDescription(normalizeDescription(share.shareDescription()));
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

    private void validateUsersBelongToEventGroup(EventJpaEntity event, List<UUID> userIds, Set<UUID> alreadyInExpense) {
        for (UUID userId : userIds) {
            if (alreadyInExpense.contains(userId)) {
                continue;
            }
            boolean participates = groupMembers.findByGroupIdAndUserId(event.getGroup().getId(), userId)
                    .filter(GroupMemberJpaEntity::isActive)
                    .map(member -> member.participatesIn(event.getId()))
                    .orElse(false);
            if (!participates) {
                throw new DomainException("Todas as pessoas da despesa precisam participar deste evento.");
            }
        }
    }

    // Integrante ativo do grupo e, se for temporario, so no proprio evento.
    private void requireEventAccess(EventJpaEntity event, UUID userId) {
        GroupMemberJpaEntity member = groupMembers.findByGroupIdAndUserId(event.getGroup().getId(), userId)
                .filter(GroupMemberJpaEntity::isActive)
                .orElseThrow(() -> new DomainException("O usuário não faz parte deste grupo."));
        if (!member.participatesIn(event.getId())) {
            throw new DomainException("Convidados temporários só acessam o próprio evento.");
        }
    }

    private void requireExpenseChangePermission(ExpenseJpaEntity expense, UUID userId) {
        if (expense.getCreatedBy().getId().equals(userId)) {
            return;
        }
        if (groupMembers.existsByGroupIdAndUserIdAndRoleAndActiveTrue(
                expense.getEvent().getGroup().getId(),
                userId,
                GroupMemberRole.ADMIN
        )) {
            return;
        }
        throw new DomainException("Apenas quem criou a despesa ou os administradores do grupo podem alterá-la.");
    }

    private EventJpaEntity resolveEvent(CreateExpenseRequest request) {
        if (request.eventId() != null) {
            return events.findById(request.eventId())
                    .orElseThrow(() -> new DomainException("Evento não encontrado."));
        }
        if (request.monthId() == null) {
            throw new DomainException("A despesa precisa estar em um evento.");
        }
        MonthJpaEntity month = months.findById(request.monthId())
                .orElseThrow(() -> new DomainException("Mês não encontrado."));
        return findOrCreateMonthlyEvent(month, defaultGroup(), LocalDateTime.now());
    }

    private MonthJpaEntity resolveMonth(CreateExpenseRequest request, EventJpaEntity event) {
        if (request.monthId() != null) {
            return months.findById(request.monthId())
                    .orElseThrow(() -> new DomainException("Mês não encontrado."));
        }
        return event.getMonth();
    }

    private void validateOpen(EventJpaEntity event) {
        if (event.getStatus() != EventStatus.OPEN) {
            throw new DomainException("Não é possível adicionar despesa a um evento que não está aberto.");
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
                .orElseThrow(() -> new DomainException("Grupo não encontrado."));
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
            throw new DomainException("O parcelamento permite apenas um pagador.");
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
            throw new DomainException("A despesa precisa ter ao menos um pagador.");
        }
        return new ArrayList<>(List.of(new ExpensePayerRequest(request.payerId(), request.amount())));
    }

    private List<ParticipantSplit> normalizeParticipants(CreateExpenseRequest request) {
        List<ParticipantSplit> participants;
        if (request.participants() != null && !request.participants().isEmpty()) {
            participants = request.participants().stream()
                    .map(participant -> new ParticipantSplit(
                            participant.userId(),
                            participant.shareCount() == null ? 1 : participant.shareCount(),
                            normalizeDescription(participant.shareDescription()),
                            participant.shareAmount()
                    ))
                    .toList();
        } else if (request.participantIds() != null && !request.participantIds().isEmpty()) {
            participants = request.participantIds().stream()
                    .map(userId -> new ParticipantSplit(userId, 1, null))
                    .toList();
        } else {
            throw new DomainException("A despesa precisa ter ao menos um participante.");
        }
        if (participants.stream().map(ParticipantSplit::userId).distinct().count() != participants.size()) {
            throw new DomainException("A despesa não pode ter participantes repetidos.");
        }
        if (participants.stream().anyMatch(participant -> participant.userId() == null)) {
            throw new DomainException("Informe o participante da despesa.");
        }
        if (participants.stream().anyMatch(participant -> participant.shareCount() <= 0)) {
            throw new DomainException("A cota do participante precisa ser maior que zero.");
        }
        if (participants.stream().anyMatch(participant ->
                participant.shareDescription() != null && participant.shareDescription().length() > 160
        )) {
            throw new DomainException("A descrição da cota pode ter no máximo 160 caracteres.");
        }
        return participants;
    }

    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void validatePayersTotal(BigDecimal amount, List<ExpensePayerRequest> payers) {
        if (payers.isEmpty()) {
            throw new DomainException("A despesa precisa ter ao menos um pagador.");
        }
        if (payers.stream().map(ExpensePayerRequest::userId).distinct().count() != payers.size()) {
            throw new DomainException("A despesa não pode ter pagadores repetidos.");
        }
        if (payers.stream().anyMatch(payer -> payer.amount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new DomainException("O valor pago precisa ser maior que zero.");
        }
        BigDecimal expected = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPaid = payers.stream()
                .map(payer -> payer.amount().setScale(2, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), BigDecimal::add);
        if (expected.compareTo(totalPaid) != 0) {
            throw new DomainException("O total pago precisa bater com o valor da despesa.");
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
                expense.getCreatedBy().getId(),
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
                                participant.getShareAmount(),
                                participant.getShareCount(),
                                participant.getShareDescription()
                        ))
                        .toList(),
                expense.getInstallmentGroup() != null && expense.getInstallmentGroup().isSubscription(),
                expense.getInstallmentGroup() != null && expense.getInstallmentGroup().getCancelledAt() != null,
                expense.isCustomSplit(),
                expense.getCreatedAt()
        );
    }
}
