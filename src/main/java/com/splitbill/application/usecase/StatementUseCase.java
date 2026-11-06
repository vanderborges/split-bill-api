package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventResponse;
import com.splitbill.application.dto.StatementResponse;
import com.splitbill.application.dto.StatementResponse.StatementEventResponse;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.EventSettlementJpaEntity;
import com.splitbill.infrastructure.persistence.entity.ExpenseJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventSettlementJpaRepository;
import com.splitbill.infrastructure.persistence.repository.ExpenseJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monta o Extrato inteiro numa chamada HTTP (antes eram 2 por evento e 1
 * por grupo, cada uma pagando a ida e volta ate o servidor). Mesmas regras
 * do relatorio e dos acertos, mas os dados de TODOS os eventos sao lidos de
 * uma vez (eventos, despesas, integrantes e acertos): o numero de consultas
 * nao cresce com o numero de eventos. Isso importa no servidor, onde cada
 * consulta ao banco custa bem mais que no ambiente local.
 *
 * <p>O acesso e checado uma vez por chamada e o saldo de cada evento e
 * calculado uma vez e serve o relatorio e os acertos.
 */
@Service
public class StatementUseCase {

    private final EventUseCase events;
    private final EventJpaRepository eventRows;
    private final ExpenseJpaRepository expenses;
    private final EventSettlementJpaRepository settlementRows;
    private final MonthlyReportUseCase reports;
    private final EventSettlementUseCase settlements;
    private final GroupUseCase groupRules;
    private final GroupMemberJpaRepository groupMembers;

    public StatementUseCase(
            EventUseCase events,
            EventJpaRepository eventRows,
            ExpenseJpaRepository expenses,
            EventSettlementJpaRepository settlementRows,
            MonthlyReportUseCase reports,
            EventSettlementUseCase settlements,
            GroupUseCase groupRules,
            GroupMemberJpaRepository groupMembers
    ) {
        this.events = events;
        this.eventRows = eventRows;
        this.expenses = expenses;
        this.settlementRows = settlementRows;
        this.reports = reports;
        this.settlements = settlements;
        this.groupRules = groupRules;
        this.groupMembers = groupMembers;
    }

    // Uma transacao so: com tudo lido de uma vez, quase nao ha consultas
    // depois (antes, com ~450, o Hibernate conferia as entidades antes de
    // cada uma). Nao e somente leitura: abrir os acertos grava a linha de
    // quem ainda nao tinha, como na chamada por evento.
    @Transactional
    public StatementResponse load(UUID viewerUserId, UUID groupId) {
        List<EventResponse> listed = events.list(viewerUserId, groupId, true);
        Map<UUID, GroupMemberJpaEntity> membershipByGroup = new HashMap<>();
        groupMembers.findByUserId(viewerUserId)
                .forEach(member -> membershipByGroup.put(member.getGroup().getId(), member));
        Set<UUID> involvedEventIds = groupRules.involvedEventIds(viewerUserId);

        // Mesma regra de GroupUseCase.requireEventAccess.
        Map<UUID, EventJpaEntity> eventsById = eventRows.findAllById(
                        listed.stream().map(EventResponse::id).toList()).stream()
                .collect(Collectors.toMap(EventJpaEntity::getId, Function.identity()));
        List<EventJpaEntity> readable = listed.stream()
                .map(item -> eventsById.get(item.id()))
                .filter(event -> event != null && groupRules.canSeeEvent(
                        membershipByGroup.get(event.getGroup().getId()), event, involvedEventIds))
                .toList();
        if (readable.isEmpty()) {
            return new StatementResponse(List.of(), Map.of());
        }

        List<UUID> eventIds = readable.stream().map(EventJpaEntity::getId).toList();
        Map<UUID, List<ExpenseJpaEntity>> expensesByEvent = expenses.findByEventIdInAndDeletedAtIsNull(eventIds)
                .stream()
                .collect(Collectors.groupingBy(expense -> expense.getEvent().getId()));
        Map<UUID, List<GroupMemberJpaEntity>> membersByGroup = groupMembers.findByGroupIdInAndActiveTrue(
                        readable.stream().map(event -> event.getGroup().getId()).distinct().toList())
                .stream()
                .collect(Collectors.groupingBy(member -> member.getGroup().getId()));
        Map<UUID, List<EventSettlementJpaEntity>> rowsByEvent = settlementRows.findByEventIdIn(eventIds).stream()
                .collect(Collectors.groupingBy(row -> row.getEvent().getId()));

        List<StatementEventResponse> result = new ArrayList<>();
        Map<UUID, String> roles = new HashMap<>();
        for (EventJpaEntity event : readable) {
            UUID eventGroupId = event.getGroup().getId();
            MonthlyReportUseCase.EventReport report = reports.reportWithBalances(event,
                    expensesByEvent.getOrDefault(event.getId(), List.of()),
                    membersByGroup.getOrDefault(eventGroupId, List.of()));
            result.add(new StatementEventResponse(
                    event.getId(),
                    report.report(),
                    settlements.syncWithBalances(event, report.balances(),
                            rowsByEvent.getOrDefault(event.getId(), List.of()))));
            GroupMemberJpaEntity member = membershipByGroup.get(eventGroupId);
            roles.put(eventGroupId, member.isActive() ? member.getRole().name() : null);
        }
        return new StatementResponse(result, roles);
    }
}
