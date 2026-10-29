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
import java.util.ArrayList;
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
                .orElseThrow(() -> new DomainException("Evento não encontrado."));
        groupRules.requireEventAccess(event, requesterId);
        LocalDateTime now = LocalDateTime.now();

        // Uma consulta so pras linhas do evento (antes era uma por pessoa).
        List<EventSettlementJpaEntity> eventSettlements = new ArrayList<>(settlements.findByEventId(eventId));
        Map<UUID, EventSettlementJpaEntity> byUser = new HashMap<>();
        eventSettlements.forEach(settlement -> byUser.put(settlement.getUser().getId(), settlement));

        for (MonthlyReportUseCase.BalanceResult balance : reports.calculateBalances(eventId)) {
            EventSettlementJpaEntity existing = byUser.get(balance.userId());
            EventSettlementJpaEntity settlement = existing;
            if (settlement == null) {
                settlement = createSettlement(event, balance.userId(), now);
                eventSettlements.add(settlement);
            }
            BigDecimal previousAmount = settlement.getAmount();
            SettlementStatus previousStatus = normalizeStatus(settlement.getStatus());
            BigDecimal amount = balance.balance().abs().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            SettlementRole role = roleFrom(balance.balance());
            // Evento fechado ja foi todo acertado: linha que so nasce agora
            // (ninguem tinha aberto o relatorio antes de fechar, ou fechado
            // por ajuste de dados como a V23) entra como paga, nao pendente.
            SettlementStatus status = existing == null && event.getStatus() == EventStatus.CLOSED
                    ? SettlementStatus.PAID
                    : defaultStatus(amount, previousAmount, previousStatus);
            // So grava quando algo mudou: abrir o relatorio fazia um UPDATE
            // por pessoa toda vez, so por causa do updatedAt.
            boolean changed = settlement.getRole() != role
                    || previousAmount == null || previousAmount.compareTo(amount) != 0
                    || settlement.getStatus() != status;
            if (changed) {
                settlement.setRole(role);
                settlement.setAmount(amount);
                settlement.setStatus(status);
                settlement.setUpdatedAt(now);
            }
        }
        syncLinkedSettlements(eventSettlements, now);

        return eventSettlements.stream()
                .sorted(Comparator.comparing(settlement -> settlement.getUser().getNickname()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EventSettlementResponse updateStatus(UUID settlementId, UpdateSettlementStatusRequest request, UUID requesterId) {
        EventSettlementJpaEntity settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new DomainException("Pagamento não encontrado."));
        groupRules.requireAdmin(settlement.getEvent().getGroup().getId(), requesterId);
        // Pagamento so se confirma com o evento aguardando pagamento: aberto
        // ainda pode receber despesa (o saldo muda) e fechado ja foi acertado.
        if (settlement.getEvent().getStatus() != EventStatus.SETTLING) {
            throw new DomainException("Só é possível alterar pagamentos com o evento aguardando pagamentos.");
        }
        UserJpaEntity admin = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Administrador não encontrado."));

        SettlementStatus status = normalizeStatus(request.status());
        // "Pagamento informado" so quem deve marca (botao Pagar); o admin
        // confirma (PAID) ou devolve pra pendente.
        if (status != SettlementStatus.PAID && status != SettlementStatus.PENDING) {
            throw new DomainException("Status de pagamento inválido.");
        }
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
     * "Concluir evento" com pagamento em aberto: o admin confirma o
     * fechamento e todos os acertos que faltavam (pendentes ou informados)
     * ficam pagos.
     */
    @Transactional
    public void markAllPaid(UUID eventId, UUID requesterId) {
        // Garante as linhas de todo mundo antes de marcar.
        listByEvent(eventId, requesterId);
        UserJpaEntity admin = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Administrador não encontrado."));
        LocalDateTime now = LocalDateTime.now();
        settlements.findByEventId(eventId).stream()
                .filter(settlement -> normalizeStatus(settlement.getStatus()) != SettlementStatus.PAID)
                .forEach(settlement -> {
                    settlement.setStatus(SettlementStatus.PAID);
                    settlement.setUpdatedByAdmin(admin);
                    settlement.setUpdatedAt(now);
                });
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
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
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
