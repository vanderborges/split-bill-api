package com.splitbill.application.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extrato com "Todos os eventos" numa chamada so: o relatorio e os acertos
 * de cada evento e o papel de quem esta vendo em cada grupo. Antes o app
 * fazia 2 chamadas por evento e 1 por grupo.
 */
public record StatementResponse(
        List<StatementEventResponse> events,
        // null = nao e mais integrante ativo (Extrato de grupo de onde saiu).
        Map<UUID, String> groupRoles
) {

    public record StatementEventResponse(
            UUID eventId,
            MonthlyReportResponse report,
            List<EventSettlementResponse> settlements
    ) {
    }
}
