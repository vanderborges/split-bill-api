package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.UpdateSettlementStatusRequest;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.SettlementRole;
import com.splitbill.domain.valueobject.SettlementStatus;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.EventSettlementJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventSettlementJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class EventSettlementUseCase {

    private static final int MONEY_SCALE = 2;

    private final EventSettlementJpaRepository settlements;
    private final EventJpaRepository events;
    private final UserJpaRepository users;
    private final MonthlyReportUseCase reports;
    private final GroupUseCase groupRules;

    public EventSettlementUseCase(
            EventSettlementJpaRepository settlements,
            EventJpaRepository events,
            UserJpaRepository users,
            MonthlyReportUseCase reports,
            GroupUseCase groupRules
    ) {
        this.settlements = settlements;
        this.events = events;
        this.users = users;
        this.reports = reports;
        this.groupRules = groupRules;
    }

    @Transactional
    public List<EventSettlementResponse> listByEvent(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireMembership(event.getGroup().getId(), requesterId);
        LocalDateTime now = LocalDateTime.now();

        for (MonthlyReportUseCase.BalanceResult balance : reports.calculateBalances(eventId)) {
            EventSettlementJpaEntity settlement = settlements.findByEventIdAndUserId(eventId, balance.userId())
                    .orElseGet(() -> createSettlement(event, balance.userId(), now));
            BigDecimal previousAmount = settlement.getAmount();
            SettlementStatus previousStatus = normalizeStatus(settlement.getStatus());
            BigDecimal amount = balance.balance().abs().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            settlement.setRole(roleFrom(balance.balance()));
            settlement.setAmount(amount);
            settlement.setUpdatedAt(now);
            settlement.setStatus(defaultStatus(amount, previousAmount, previousStatus));
        }

        return settlements.findByEventId(eventId).stream()
                .sorted(Comparator.comparing(settlement -> settlement.getUser().getNickname()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EventSettlementResponse updateStatus(UUID settlementId, UpdateSettlementStatusRequest request, UUID requesterId) {
        EventSettlementJpaEntity settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new DomainException("Settlement not found"));
        groupRules.requireAdmin(settlement.getEvent().getGroup().getId(), requesterId);
        UserJpaEntity admin = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Admin user not found"));

        settlement.setStatus(normalizeStatus(request.status()));
        settlement.setUpdatedByAdmin(admin);
        settlement.setUpdatedAt(LocalDateTime.now());
        return toResponse(settlement);
    }

    private EventSettlementJpaEntity createSettlement(EventJpaEntity event, UUID userId, LocalDateTime now) {
        UserJpaEntity user = users.findById(userId)
                .orElseThrow(() -> new DomainException("User not found"));
        EventSettlementJpaEntity settlement = new EventSettlementJpaEntity();
        settlement.setId(UUID.randomUUID());
        settlement.setEvent(event);
        settlement.setUser(user);
        settlement.setRole(SettlementRole.NEUTRAL);
        settlement.setAmount(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        settlement.setStatus(SettlementStatus.PENDING);
        settlement.setCreatedAt(now);
        settlement.setUpdatedAt(now);
        return settlements.save(settlement);
    }

    private SettlementRole roleFrom(BigDecimal balance) {
        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            return SettlementRole.CREDITOR;
        }
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            return SettlementRole.DEBTOR;
        }
        return SettlementRole.NEUTRAL;
    }

    private SettlementStatus normalizeStatus(SettlementStatus status) {
        if (status == null) {
            return SettlementStatus.PENDING;
        }
        if (status == SettlementStatus.PAID_TO_ADMIN
                || status == SettlementStatus.RECEIVED_FROM_ADMIN
                || status == SettlementStatus.CONFIRMED) {
            return SettlementStatus.PAID;
        }
        return status;
    }

    private SettlementStatus defaultStatus(
            BigDecimal amount,
            BigDecimal previousAmount,
            SettlementStatus previousStatus
    ) {
        if (amount.compareTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0) {
            return SettlementStatus.PAID;
        }
        if (previousAmount == null
                || previousAmount.compareTo(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0) {
            return SettlementStatus.PENDING;
        }
        return previousStatus;
    }

    private EventSettlementResponse toResponse(EventSettlementJpaEntity settlement) {
        return new EventSettlementResponse(
                settlement.getId(),
                settlement.getEvent().getId(),
                settlement.getUser().getId(),
                settlement.getUser().getNickname(),
                settlement.getRole(),
                settlement.getAmount(),
                settlement.getStatus(),
                settlement.getUpdatedByAdmin() == null ? null : settlement.getUpdatedByAdmin().getId(),
                settlement.getUpdatedAt()
        );
    }
}
