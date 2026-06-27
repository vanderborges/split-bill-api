package com.splitbill.application.usecase;

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

    @Transactional(readOnly = true)
    public List<DashboardGroupBalanceResponse> getGroupBalances(UUID userId) {
        List<GroupJpaEntity> userGroups = groupMembers.findByUserIdAndActiveTrue(userId).stream()
                .map(GroupMemberJpaEntity::getGroup)
                .filter(group -> group.isActive())
                .toList();

        Map<UUID, GroupBalance> balancesByGroup = new LinkedHashMap<>();
        userGroups.forEach(group -> balancesByGroup.put(group.getId(), new GroupBalance(group)));
        if (userGroups.isEmpty()) {
            return List.of();
        }

        List<UUID> groupIds = userGroups.stream().map(GroupJpaEntity::getId).toList();
        List<EventJpaEntity> openEvents = events.findByGroupIdInAndDeletedAtIsNull(groupIds).stream()
                .filter(event -> event.getStatus() == EventStatus.OPEN)
                .toList();
        if (openEvents.isEmpty()) {
            return toResponses(balancesByGroup);
        }

        Map<UUID, UUID> groupIdByEventId = new LinkedHashMap<>();
        openEvents.forEach(event -> groupIdByEventId.put(event.getId(), event.getGroup().getId()));

        List<UUID> eventIds = openEvents.stream().map(EventJpaEntity::getId).toList();
        for (ExpenseJpaEntity expense : expenses.findByEventIdInAndDeletedAtIsNull(eventIds)) {
            UUID groupId = groupIdByEventId.get(expense.getEvent().getId());
            GroupBalance groupBalance = balancesByGroup.get(groupId);
            if (groupBalance == null) {
                continue;
            }

            expense.getParticipants().stream()
                    .filter(participant -> participant.getUser().getId().equals(userId))
                    .forEach(participant -> groupBalance.addConsumed(participant.getShareAmount()));

            expense.getPayers().stream()
                    .filter(payer -> payer.getUser().getId().equals(userId))
                    .forEach(payer -> groupBalance.addPaid(payer.getPaidAmount()));
        }

        return toResponses(balancesByGroup);
    }

    private List<DashboardGroupBalanceResponse> toResponses(Map<UUID, GroupBalance> balancesByGroup) {
        return balancesByGroup.values().stream()
                .map(GroupBalance::toResponse)
                .toList();
    }

    private static final class GroupBalance {
        private final GroupJpaEntity group;
        private BigDecimal consumed = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal paid = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        private GroupBalance(GroupJpaEntity group) {
            this.group = group;
        }

        private void addConsumed(BigDecimal amount) {
            consumed = consumed.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private void addPaid(BigDecimal amount) {
            paid = paid.add(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        private DashboardGroupBalanceResponse toResponse() {
            return new DashboardGroupBalanceResponse(
                    group.getId(),
                    group.getName(),
                    paid.subtract(consumed).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
            );
        }
    }
}
