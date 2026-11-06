package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.dto.StatementResponse;
import com.splitbill.application.dto.StatementResponse.StatementEventResponse;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Monta o Extrato inteiro numa chamada HTTP (antes eram 2 por evento e 1
 * por grupo, cada uma pagando a ida e volta ate o servidor). Mesmas regras
 * do relatorio e dos acertos, mas:
 * <ul>
 *   <li>o acesso e checado uma vez por chamada (antes, 3 vezes por evento,
 *   cada uma refazendo a consulta de eventos com despesa da pessoa);</li>
 *   <li>o saldo de cada evento e calculado uma vez e serve o relatorio e os
 *   acertos (antes os acertos recalculavam tudo);</li>
 *   <li>cada evento roda numa transacao curta: uma transacao so fazia o
 *   Hibernate conferir centenas de entidades antes de cada consulta.</li>
 * </ul>
 */
@Service
public class StatementUseCase {

    private final EventUseCase events;
    private final EventJpaRepository eventRows;
    private final MonthlyReportUseCase reports;
    private final EventSettlementUseCase settlements;
    private final GroupUseCase groupRules;
    private final GroupMemberJpaRepository groupMembers;
    private final TransactionTemplate perEvent;

    public StatementUseCase(
            EventUseCase events,
            EventJpaRepository eventRows,
            MonthlyReportUseCase reports,
            EventSettlementUseCase settlements,
            GroupUseCase groupRules,
            GroupMemberJpaRepository groupMembers,
            PlatformTransactionManager transactionManager
    ) {
        this.events = events;
        this.eventRows = eventRows;
        this.reports = reports;
        this.settlements = settlements;
        this.groupRules = groupRules;
        this.groupMembers = groupMembers;
        this.perEvent = new TransactionTemplate(transactionManager);
    }

    public StatementResponse load(UUID viewerUserId, UUID groupId) {
        List<EventResponse> listed = events.list(viewerUserId, groupId, true);
        Map<UUID, GroupMemberJpaEntity> membershipByGroup = new HashMap<>();
        groupMembers.findByUserId(viewerUserId)
                .forEach(member -> membershipByGroup.put(member.getGroup().getId(), member));
        Set<UUID> involvedEventIds = groupRules.involvedEventIds(viewerUserId);

        List<StatementEventResponse> result = new ArrayList<>();
        Map<UUID, String> roles = new HashMap<>();
        for (EventResponse item : listed) {
            GroupMemberJpaEntity member = membershipByGroup.get(item.groupId());
            StatementEventResponse loaded = perEvent.execute(status -> {
                EventJpaEntity event = eventRows.findById(item.id()).orElse(null);
                // Mesma regra de GroupUseCase.requireEventAccess.
                if (event == null || member == null || !groupRules.canSeeEvent(member, event, involvedEventIds)) {
                    return null;
                }
                MonthlyReportUseCase.EventReport report = reports.reportWithBalances(event);
                return new StatementEventResponse(
                        event.getId(),
                        report.report(),
                        settlements.syncWithBalances(event, report.balances()));
            });
            if (loaded != null) {
                result.add(loaded);
                roles.put(item.groupId(), member.isActive() ? member.getRole().name() : null);
            }
        }
        return new StatementResponse(result, roles);
    }
}
