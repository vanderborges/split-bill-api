package com.splitbill.application.usecase;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.service.ExpenseSplitCalculator;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.domain.valueobject.ParticipantShare;
import com.splitbill.domain.valueobject.ParticipantSplit;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseParticipantJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpensePayerJpaEntity;
import com.splitbill.infrastructure.persistence.entity.InstallmentGroupJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Gera a proxima parcela/assinatura de uma despesa mensal. Usado em dois
 * momentos:
 * <ul>
 *   <li>no "Fechar evento" ({@link EventUseCase#startSettlement}), que rola
 *   a proxima parcela pro evento seguinte (criando-o se preciso);</li>
 *   <li>sempre que ja existe um evento mensal seguinte ABERTO (dois meses
 *   abertos ao mesmo tempo): a proxima parcela ja entra nele na hora - ao
 *   criar esse evento, ao lancar a despesa parcelada e ao edita-la.</li>
 * </ul>
 * Nunca duplica: se o numero da proxima parcela ja existe em qualquer evento,
 * nao cria de novo. Parcela apagada pelo usuario (soft delete) encerra a
 * serie dali pra frente - nao volta no fechamento do mes anterior.
 */
@Service
public class InstallmentRolloverService {

    private final ExpenseJpaRepository expenses;
    private final EventJpaRepository events;
    private final ExpenseSplitCalculator splitCalculator = new ExpenseSplitCalculator();

    public InstallmentRolloverService(ExpenseJpaRepository expenses, EventJpaRepository events) {
        this.expenses = expenses;
        this.events = events;
    }

    /**
     * Numero da proxima parcela de {@code current}, se a serie continua e ela
     * ainda nao existe (nem foi apagada pelo usuario).
     */
    public Optional<Integer> pendingNextNumber(ExpenseJpaEntity current) {
        InstallmentGroupJpaEntity installmentGroup = current.getInstallmentGroup();
        if (installmentGroup == null || current.getInstallmentNumber() == null) {
            return Optional.empty();
        }
        int next = current.getInstallmentNumber() + 1;
        if (!seriesContinues(current, next)) {
            return Optional.empty();
        }
        if (activeByNumber(installmentGroup.getId()).containsKey(next) || deletedByUser(installmentGroup.getId(), next)) {
            return Optional.empty();
        }
        return Optional.of(next);
    }

    /**
     * Cria a parcela {@code installmentNumber} em {@code targetEvent} a partir
     * de {@code template} (participantes, pagador e categoria dele; valor e
     * descricao do grupo de parcelas).
     */
    public ExpenseJpaEntity createNext(ExpenseJpaEntity template, EventJpaEntity targetEvent, int installmentNumber) {
        if (targetEvent.getStatus() == EventStatus.CLOSED) {
            throw new DomainException("Não é possível criar a próxima parcela em um evento mensal fechado.");
        }
        InstallmentGroupJpaEntity installmentGroup = template.getInstallmentGroup();
        BigDecimal amount = installmentGroup.getTotalAmount();
        // Divisao por valor: a proxima parcela repete os mesmos valores por
        // pessoa (so faz sentido se o valor da parcela for o mesmo; se nao
        // for, cai na divisao por cotas).
        boolean copyAmounts = template.isCustomSplit() && template.getAmount().compareTo(amount) == 0;
        List<ParticipantSplit> participants = template.getParticipants().stream()
                .map(participant -> new ParticipantSplit(
                        participant.getUser().getId(),
                        participant.getShareCount(),
                        participant.getShareDescription(),
                        copyAmounts ? participant.getShareAmount() : null
                ))
                .toList();
        // Mantem a data cadastrada originalmente (a da 1a parcela) em vez de
        // mover pro mes do evento de destino - assim a parcela que tombou
        // fica no fim da lista (ordenada da mais recente pra mais antiga).
        LocalDate originalDate = activeByNumber(installmentGroup.getId())
                .getOrDefault(1, template)
                .getExpenseDate();

        LocalDateTime now = LocalDateTime.now();
        ExpenseJpaEntity expense = new ExpenseJpaEntity();
        expense.setId(UUID.randomUUID());
        expense.setDescription(installmentGroup.getDescription());
        expense.setAmount(amount);
        expense.setExpenseDate(originalDate);
        expense.setCategory(template.getCategory());
        expense.setPayer(template.getPayer());
        expense.setCreatedBy(template.getCreatedBy());
        expense.setMonth(targetEvent.getMonth());
        expense.setEvent(targetEvent);
        expense.setInstallmentGroup(installmentGroup);
        expense.setInstallmentNumber(installmentNumber);
        expense.setTotalInstallments(template.getTotalInstallments());
        expense.setCustomSplit(participants.stream().anyMatch(participant -> participant.fixedAmount() != null));
        expense.setCreatedAt(now);
        expense.setUpdatedAt(now);

        for (ParticipantShare share : splitCalculator.splitByShares(amount, participants)) {
            ExpenseParticipantJpaEntity participant = new ExpenseParticipantJpaEntity();
            participant.setId(UUID.randomUUID());
            participant.setExpense(expense);
            participant.setUser(template.getParticipants().stream()
                    .filter(item -> item.getUser().getId().equals(share.userId()))
                    .findFirst()
                    .orElseThrow(() -> new DomainException("Participante da parcela não encontrado."))
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

        return expenses.save(expense);
    }

    /**
     * Com mais de um mes aberto, ja coloca as proximas parcelas de
     * {@code expense} nos eventos mensais seguintes que estiverem OPEN, em
     * sequencia (01 -> 02 -> 03...). Para no primeiro mes seguinte que nao
     * existe ou nao esta aberto - dali pra frente quem rola e o "Fechar evento".
     */
    public void propagateForward(ExpenseJpaEntity expense) {
        if (expense.getInstallmentGroup() == null || expense.getInstallmentNumber() == null
                || !isMonthly(expense.getEvent())) {
            return;
        }
        UUID installmentGroupId = expense.getInstallmentGroup().getId();
        ExpenseJpaEntity current = expense;
        while (true) {
            int next = current.getInstallmentNumber() + 1;
            if (!seriesContinues(current, next)) {
                return;
            }
            ExpenseJpaEntity existing = activeByNumber(installmentGroupId).get(next);
            if (existing != null) {
                // Ja existe (ex.: rolada antes): segue a cadeia a partir dela,
                // desde que esteja num mes depois do atual.
                if (!isMonthly(existing.getEvent())
                        || !referenceOf(existing.getEvent()).isAfter(referenceOf(current.getEvent()))) {
                    return;
                }
                current = existing;
                continue;
            }
            if (deletedByUser(installmentGroupId, next)) {
                return;
            }
            Optional<EventJpaEntity> target = nextMonthlyEvent(current.getEvent())
                    .filter(event -> event.getStatus() == EventStatus.OPEN);
            if (target.isEmpty()) {
                return;
            }
            current = createNext(current, target.get(), next);
        }
    }

    /**
     * Remove as parcelas seguintes a {@code expense} que ja tinham sido
     * colocadas em eventos ainda abertos - usado ao editar (pra recriar com os
     * dados novos), apagar a parcela ou cancelar a assinatura. Sao copias
     * geradas pelo sistema, entao saem de vez (nao soft delete), pra nao
     * serem confundidas com uma parcela que o usuario apagou.
     */
    public void discardForward(ExpenseJpaEntity expense) {
        if (expense.getInstallmentGroup() == null || expense.getInstallmentNumber() == null) {
            return;
        }
        expenses.findByInstallmentGroupIdAndDeletedAtIsNull(expense.getInstallmentGroup().getId()).stream()
                .filter(other -> !other.getId().equals(expense.getId()))
                .filter(other -> other.getInstallmentNumber() != null
                        && other.getInstallmentNumber() > expense.getInstallmentNumber())
                .filter(other -> other.getEvent().getStatus() == EventStatus.OPEN)
                .forEach(expenses::delete);
        expenses.flush();
    }

    /** Evento mensal (nao apagado) do grupo imediatamente depois de {@code event}. */
    public Optional<EventJpaEntity> nextMonthlyEvent(EventJpaEntity event) {
        YearMonth reference = referenceOf(event);
        return monthlyEventsOf(event.getGroup().getId()).stream()
                .filter(candidate -> referenceOf(candidate).isAfter(reference))
                .min(Comparator.comparing(InstallmentRolloverService::referenceOf));
    }

    /** Evento mensal (nao apagado) do grupo imediatamente antes de {@code event}. */
    public Optional<EventJpaEntity> previousMonthlyEvent(EventJpaEntity event) {
        YearMonth reference = referenceOf(event);
        return monthlyEventsOf(event.getGroup().getId()).stream()
                .filter(candidate -> referenceOf(candidate).isBefore(reference))
                .max(Comparator.comparing(InstallmentRolloverService::referenceOf));
    }

    private List<EventJpaEntity> monthlyEventsOf(UUID groupId) {
        return events.findByGroupIdAndDeletedAtIsNull(groupId).stream()
                .filter(InstallmentRolloverService::isMonthly)
                .toList();
    }

    // Assinatura: sem numero fixo de parcelas, so para quando alguem cancela.
    // Parcelamento comum: para quando atinge o total contratado.
    private static boolean seriesContinues(ExpenseJpaEntity current, int next) {
        InstallmentGroupJpaEntity installmentGroup = current.getInstallmentGroup();
        if (installmentGroup.isSubscription()) {
            return installmentGroup.getCancelledAt() == null;
        }
        return current.getTotalInstallments() != null && next <= current.getTotalInstallments();
    }

    private Map<Integer, ExpenseJpaEntity> activeByNumber(UUID installmentGroupId) {
        return expenses.findByInstallmentGroupIdAndDeletedAtIsNull(installmentGroupId).stream()
                .filter(expense -> expense.getInstallmentNumber() != null)
                .collect(Collectors.toMap(ExpenseJpaEntity::getInstallmentNumber, Function.identity(),
                        (first, ignored) -> first));
    }

    // Apagada pelo usuario = soft delete com o evento ainda vivo (apagar o
    // evento inteiro tambem apaga as despesas, mas isso nao encerra a serie).
    private boolean deletedByUser(UUID installmentGroupId, int installmentNumber) {
        return expenses.findByInstallmentGroupId(installmentGroupId).stream()
                .anyMatch(expense -> expense.getDeletedAt() != null
                        && Integer.valueOf(installmentNumber).equals(expense.getInstallmentNumber())
                        && expense.getEvent().getDeletedAt() == null);
    }

    private static boolean isMonthly(EventJpaEntity event) {
        return event != null && event.getType() == EventType.MONTHLY && event.getMonth() != null;
    }

    private static YearMonth referenceOf(EventJpaEntity event) {
        return YearMonth.of(event.getMonth().getYear(), event.getMonth().getMonth());
    }
}
