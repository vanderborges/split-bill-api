package com.splitbill.application.usecase;

import com.splitbill.application.dto.DashboardEventBalanceResponse;
import com.splitbill.application.dto.DashboardGroupBalanceResponse;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DashboardUseCase {

    private static final int MONEY_SCALE = 2;

    private final GroupMemberJpaRepository groupMembers;
    private final EventJpaRepository events;
    private final ExpenseJpaRepository expenses;

    public DashboardUseCase(
            GroupMemberJpaRepository groupMembers,
            EventJpaRepository events,
            ExpenseJpaRepository expenses
    ) {
        this.groupMembers = groupMembers;
        this.events = events;
        this.expenses = expenses;
    }

    /**
     * Antes isso somava o saldo de todos os eventos em aberto num único
     * número por grupo - confuso quando um grupo tem mais de um evento
     * aberto ao mesmo tempo (ex.: dois meses, ou um mês + um evento
     * avulso), porque o número combinado escondia o que estava
     * acontecendo em cada evento. Agora retorna, por grupo, a lista dos
     * eventos em que a pessoa realmente tem despesa (pagou ou consumiu
     * algo), cada um com o próprio saldo - a tela decide como agrupar
     * visualmente.
     */
    @Transactional(readOnly = true)
    public List<DashboardGroupBalanceResponse> getGroupBalances(UUID userId) {
        List<GroupJpaEntity> userGroups = groupMembers.findByUserIdAndActiveTrue(userId).stream()
                .map(GroupMemberJpaEntity::getGroup)
                .filter(GroupJpaEntity::isActive)
                .toList();
        if (userGroups.isEmpty()) {
            return List.of();
        }

        List<UUID> groupIds = userGroups.stream().map(GroupJpaEntity::getId).toList();
        // Inclui SETTLING junto com OPEN: o saldo ainda esta pendente de pagamento
        // ate o evento ser de fato fechado, mesmo que novas despesas ja estejam
        // congeladas.
        List<EventJpaEntity> activeEvents = events.findByGroupIdInAndDeletedAtIsNull(groupIds).stream()
                .filter(event -> event.getStatus() != EventStatus.CLOSED)
                .toList();

        Map<UUID, EventBalance> balanceByEventId = new LinkedHashMap<>();
        activeEvents.forEach(event -> balanceByEventId.put(event.getId(), new EventBalance(event)));

        if (!activeEvents.isEmpty()) {
            List<UUID> eventIds = activeEvents.stream().map(EventJpaEntity::getId).toList();
            for (ExpenseJpaEntity expense : expenses.findByEventIdInAndDeletedAtIsNull(eventIds)) {
                EventBalance eventBalance = balanceByEventId.get(expense.getEvent().getId());
                if (eventBalance == null) {
                    continue;
                }
                expense.getParticipants().stream()
                        .filter(participant -> participant.getUser().getId().equals(userId))
                        .forEach(participant -> eventBalance.addConsumed(participant.getShareAmount()));
                expense.getPayers().stream()
                        .filter(payer -> payer.getUser().getId().equals(userId))
                        .forEach(payer -> eventBalance.addPaid(payer.getPaidAmount()));
            }
        }

        Map<UUID, List<EventBalance>> eventBalancesByGroupId = new LinkedHashMap<>();
        balanceByEventId.values().stream()
                .filter(EventBalance::hasActivity)
                .forEach(eventBalance -> eventBalancesByGroupId
                        .computeIfAbsent(eventBalance.event.getGroup().getId(), ignored -> new ArrayList<>())
                        .add(eventBalance));

        return userGroups.stream()
                .map(group -> new DashboardGroupBalanceResponse(
                        group.getId(),
                        group.getName(),
                        eventBalancesByGroupId.getOrDefault(group.getId(), List.of()).stream()
                                .sorted(Comparator.comparing(eventBalance -> eventBalance.event.getCreatedAt()))
                                .map(EventBalance::toResponse)
                                .toList()
                ))
                .toList();
    }

    private static final class EventBalance {
        private final EventJpaEntity event;
        private BigDecimal consumed = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal paid = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        private EventBalance(EventJpaEntity event) {
            this.event = event;
        }

        private void addConsumed(BigDecimal amount) {
            consumed = consumed.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void addPaid(BigDecimal amount) {
            paid = paid.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private boolean hasActivity() {
            BigDecimal zero = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return consumed.compareTo(zero) != 0 || paid.compareTo(zero) != 0;
        }

        private DashboardEventBalanceResponse toResponse() {
            return new DashboardEventBalanceResponse(
                    event.getId(),
                    event.getName(),
                    event.getStatus().name(),
                    paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            );
        }
    }
}
