package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.UpdateSettlementStatusRequest;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.EventStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        groupRules.requireEventAccess(event, requesterId);
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
        syncLinkedSettlements(settlements.findByEventId(eventId), now);

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
        // Pagamento so se confirma com o evento aguardando pagamento: aberto
        // ainda pode receber despesa (o saldo muda) e fechado ja foi acertado.
        if (settlement.getEvent().getStatus() != EventStatus.SETTLING) {
            throw new DomainException("So e possivel alterar pagamentos com o evento aguardando pagamento");
        }
        UserJpaEntity admin = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Admin user not found"));

        SettlementStatus status = normalizeStatus(request.status());
        LocalDateTime now = LocalDateTime.now();
        settlement.setStatus(status);
        settlement.setUpdatedByAdmin(admin);
        settlement.setUpdatedAt(now);

        // Pessoas com cobranca vinculada a este titular acompanham o status
        // dele - o saldo delas ja esta somado na linha do titular.
        UUID rootId = reports.billingRootId(settlement.getUser());
        settlements.findByEventId(settlement.getEvent().getId()).stream()
                .filter(linked -> !linked.getId().equals(settlement.getId()))
                .filter(linked -> reports.billingRootId(linked.getUser()).equals(rootId))
                .forEach(linked -> {
                    linked.setStatus(status);
                    linked.setUpdatedByAdmin(admin);
                    linked.setUpdatedAt(now);
                });
        return toResponse(settlement);
    }

    /**
     * Linha de acerto de quem tem cobranca vinculada (ex.: criada antes do
     * vinculo) espelha o status do titular. Sem isso ela ficava PENDING pra
     * sempre - invisivel no relatorio, mas travando o fechamento automatico
     * e recebendo alerta de cobranca.
     */
    private void syncLinkedSettlements(List<EventSettlementJpaEntity> eventSettlements, LocalDateTime now) {
        Map<UUID, SettlementStatus> statusByRoot = new HashMap<>();
        for (EventSettlementJpaEntity settlement : eventSettlements) {
            UUID userId = settlement.getUser().getId();
            if (reports.billingRootId(settlement.getUser()).equals(userId)) {
                statusByRoot.put(userId, normalizeStatus(settlement.getStatus()));
            }
        }
        for (EventSettlementJpaEntity settlement : eventSettlements) {
            UUID rootId = reports.billingRootId(settlement.getUser());
            SettlementStatus rootStatus = statusByRoot.get(rootId);
            if (rootId.equals(settlement.getUser().getId()) || rootStatus == null
                    || normalizeStatus(settlement.getStatus()) == rootStatus) {
                continue;
            }
            settlement.setStatus(rootStatus);
            settlement.setUpdatedAt(now);
        }
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
