package com.splitbill.application.usecase;

import com.splitbill.application.dto.BalanceExpenseDetailResponse;
import com.splitbill.application.dto.MonthlyBalanceResponse;
import com.splitbill.application.dto.MonthlyReportResponse;
import com.splitbill.application.dto.PaymentSuggestionResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.EventType;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseParticipantJpaEntity;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        groupRules.requireEventAccess(event, requesterId);
        return buildReport(event, month);
    }

    @Transactional(readOnly = true)
    public MonthlyReportResponse getByEvent(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireEventAccess(event, requesterId);
        MonthJpaEntity month = event.getMonth();
        return buildReport(event, month);
    }

    @Transactional(readOnly = true)
    public List<BalanceExpenseDetailResponse> getBalanceDetails(UUID eventId, UUID billingUserId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireEventAccess(event, requesterId);
        Set<UUID> groupedUserIds = billingGroupUserIds(event.getGroup().getId(), billingUserId);
        if (groupedUserIds.isEmpty()) {
            throw new DomainException("Balance user not found in event group");
        }

        return expenses.findByEventIdAndDeletedAtIsNull(eventId).stream()
                .map(expense -> toBalanceDetail(expense, groupedUserIds))
                .filter(detail -> detail.consumed().compareTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) != 0
                        || detail.paid().compareTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) != 0)
                .sorted(Comparator.comparing(BalanceExpenseDetailResponse::expenseDate)
                        .thenComparing(BalanceExpenseDetailResponse::description))
                .toList();
    }

    /**
     * Sugestão de pagamentos (tipo outros apps de divisão de conta): não
     * muda nada no cálculo de saldo/acerto por trás, é só uma exibição de
     * "quem paga quanto pra quem" sob demanda. Sem recebedor eleito, casa
     * devedores com credores minimizando o número de transferências. Com
     * recebedor eleito ({@link GroupJpaEntity#getReceiver}), todo devedor
     * manda o valor direto pra essa pessoa - ver
     * {@link GroupUseCase#setReceiver}.
     */
    @Transactional(readOnly = true)
    public List<PaymentSuggestionResponse> getPaymentSuggestions(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireEventAccess(event, requesterId);

        List<MonthlyBalanceResponse> activeBalances = calculateTotals(eventId).values().stream()
                .filter(BalanceTotals::hasActivity)
                .map(BalanceTotals::toResponse)
                .toList();

        UserJpaEntity receiver = event.getGroup().getReceiver();
        if (receiver != null) {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return activeBalances.stream()
                    .filter(balance -> balance.balance().compareTo(zero) < 0
                            && !balance.userId().equals(receiver.getId()))
                    .map(balance -> new PaymentSuggestionResponse(
                            balance.userId(),
                            balance.nickname(),
                            receiver.getId(),
                            receiver.getNickname(),
                            balance.balance().abs()))
                    .toList();
        }

        return matchDebtorsToCreditors(activeBalances);
    }

    private List<PaymentSuggestionResponse> matchDebtorsToCreditors(List<MonthlyBalanceResponse> balances) {
        BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        Queue<CreditSlice> creditors = new ArrayDeque<>(balances.stream()
                .filter(balance -> balance.balance().compareTo(zero) > 0)
                .map(balance -> new CreditSlice(balance.userId(), balance.nickname(), balance.balance()))
                .toList());

        List<PaymentSuggestionResponse> suggestions = new ArrayList<>();
        for (MonthlyBalanceResponse debtor : balances.stream()
                .filter(balance -> balance.balance().compareTo(zero) < 0)
                .toList()) {
            BigDecimal remaining = debtor.balance().abs();
            while (remaining.compareTo(zero) > 0 && !creditors.isEmpty()) {
                CreditSlice creditor = creditors.poll();
                BigDecimal amount = remaining.min(creditor.amount()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                suggestions.add(new PaymentSuggestionResponse(
                        debtor.userId(), debtor.nickname(), creditor.userId(), creditor.nickname(), amount));
                remaining = remaining.subtract(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                BigDecimal leftover = creditor.amount().subtract(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                if (leftover.compareTo(zero) > 0) {
                    creditors.add(new CreditSlice(creditor.userId(), creditor.nickname(), leftover));
                }
            }
        }
        return suggestions;
    }

    private record CreditSlice(UUID userId, String nickname, BigDecimal amount) {
    }

    @Transactional(readOnly = true)
    public List<BalanceResult> calculateBalances(UUID eventId) {
        return calculateTotals(eventId).values().stream()
                .filter(BalanceTotals::hasActivity)
                .map(BalanceTotals::toResult)
                .toList();
    }

    private MonthlyReportResponse buildReport(EventJpaEntity event, MonthJpaEntity month) {
        Map<UUID, BalanceTotals> totalsByUser = calculateTotals(event.getId());
        BigDecimal totalExpenses = expenses.findByEventIdAndDeletedAtIsNull(event.getId()).stream()
                .map(ExpenseJpaEntity::getAmount)
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // So exclui quem nunca participou de nenhuma despesa deste evento
        // (nem pagou, nem consumiu). Quem participou e a conta zerou
        // (pagou exatamente o que consumiu) continua aparecendo - o
        // EventSettlementUseCase ja trata saldo zero como quitado
        // automaticamente.
        List<MonthlyBalanceResponse> balances = totalsByUser.values().stream()
                .filter(BalanceTotals::hasActivity)
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
        Map<UUID, BalanceTotals> rawTotalsByUser = calculateRawTotals(eventId);
        Map<UUID, BalanceTotals> totalsByBillingUser = new LinkedHashMap<>();

        rawTotalsByUser.values().forEach(total -> {
            UserJpaEntity billingUser = resolveBillingUser(total.user);
            totalsByBillingUser.computeIfAbsent(billingUser.getId(), ignored -> new BalanceTotals(billingUser))
                    .merge(total);
        });

        return totalsByBillingUser;
    }

    private Map<UUID, BalanceTotals> calculateRawTotals(UUID eventId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        Map<UUID, BalanceTotals> totalsByUser = new LinkedHashMap<>();
        groupMembers.findByGroupIdAndActiveTrue(event.getGroup().getId()).stream()
                // Temporario de outro evento nao entra (nem com saldo zerado).
                .filter(member -> member.participatesIn(eventId))
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

    private UserJpaEntity resolveBillingUser(UserJpaEntity user) {
        Set<UUID> visited = new HashSet<>();
        UserJpaEntity current = user;
        while (current.getBillingUser() != null) {
            if (!visited.add(current.getId())) {
                throw new DomainException("Invalid billing user link");
            }
            current = current.getBillingUser();
        }
        return current;
    }

    private Set<UUID> billingGroupUserIds(UUID groupId, UUID billingUserId) {
        return groupMembers.findByGroupIdAndActiveTrue(groupId).stream()
                .map(GroupMemberJpaEntity::getUser)
                .filter(user -> user.getDeletedAt() == null)
                .filter(user -> resolveBillingUser(user).getId().equals(billingUserId))
                .map(UserJpaEntity::getId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private BalanceExpenseDetailResponse toBalanceDetail(ExpenseJpaEntity expense, Set<UUID> userIds) {
        BigDecimal consumed = expense.getParticipants().stream()
                .filter(participant -> userIds.contains(participant.getUser().getId()))
                .map(participant -> participant.getShareAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        BigDecimal paid = expense.getPayers().stream()
                .filter(payer -> userIds.contains(payer.getUser().getId()))
                .map(payer -> payer.getPaidAmount().setScale(MONEY_SCALE, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP), BigDecimal::add);
        BigDecimal impact = paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        int shareCount = expense.getParticipants().stream()
                .filter(participant -> userIds.contains(participant.getUser().getId()))
                .mapToInt(participant -> participant.getShareCount() == null ? 1 : participant.getShareCount())
                .sum();
        int totalShares = expense.getParticipants().stream()
                .mapToInt(participant -> participant.getShareCount() == null ? 1 : participant.getShareCount())
                .sum();
        String shareDescription = expense.getParticipants().stream()
                .filter(participant -> userIds.contains(participant.getUser().getId()))
                .map(ExpenseParticipantJpaEntity::getShareDescription)
                .filter(description -> description != null && !description.isBlank())
                .collect(Collectors.joining("; "));
        InstallmentGroupJpaEntity installmentGroup = expense.getInstallmentGroup();
        return new BalanceExpenseDetailResponse(
                expense.getId(),
                expense.getDescription(),
                expense.getExpenseDate(),
                expense.getCategory(),
                expense.getAmount(),
                consumed,
                paid,
                impact,
                expense.getInstallmentNumber(),
                expense.getTotalInstallments(),
                installmentGroup != null && installmentGroup.isSubscription(),
                installmentGroup != null && installmentGroup.getCancelledAt() != null,
                shareCount,
                totalShares,
                shareDescription.isEmpty() ? null : shareDescription
        );
    }

    private EventJpaEntity createMonthlyEvent(MonthJpaEntity month) {
        EventJpaEntity event = new EventJpaEntity();
        event.setId(UUID.randomUUID());
        event.setName(String.format("%02d/%d", month.getMonth(), month.getYear()));
        event.setDescription("Evento mensal criado automaticamente");
        event.setType(EventType.MONTHLY);
        // Status proprio do evento/grupo, nao do Month compartilhado entre
        // grupos - ver nota em ExpenseUseCase#validateOpen.
        event.setStatus(EventStatus.OPEN);
        event.setMonth(month);
        event.setGroup(defaultGroup());
        event.setCreatedAt(month.getCreatedAt());
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
        private final Map<UUID, String> nicknamesByUser = new LinkedHashMap<>();
        private BigDecimal consumed = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal paid = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        private BalanceTotals(UserJpaEntity user) {
            this.user = user;
            this.nicknamesByUser.put(user.getId(), user.getNickname());
        }

        private void addConsumed(BigDecimal amount) {
            consumed = consumed.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void addPaid(BigDecimal amount) {
            paid = paid.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        /** True se a pessoa pagou ou consumiu algo neste evento - mesmo que o saldo líquido seja zero. */
        private boolean hasActivity() {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return consumed.compareTo(zero) != 0 || paid.compareTo(zero) != 0;
        }

        private void merge(BalanceTotals totals) {
            consumed = consumed.add(totals.consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            paid = paid.add(totals.paid).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            nicknamesByUser.putAll(totals.nicknamesByUser);
        }

        private MonthlyBalanceResponse toResponse() {
            BigDecimal balance = paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            String nickname = String.join(" + ", nicknamesByUser.values().stream().sorted().toList());
            return new MonthlyBalanceResponse(user.getId(), nickname, consumed, paid, balance);
        }

        private BalanceResult toResult() {
            BigDecimal balance = paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return new BalanceResult(user.getId(), consumed, paid, balance);
        }
    }
}
