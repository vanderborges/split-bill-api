package com.splitbill.infrastructure.scheduling;

import com.splitbill.domain.exception.DomainException;
import com.splitbill.application.usecase.EventUseCase;
import com.splitbill.application.usecase.NotificationUseCase;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupJpaEntity;
import com.splitbill.infrastructure.persistence.repository.GroupJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Fechamento automático por grupo (ver {@code GroupUseCase#setAutoSettlementDay}):
 * no dia do mês configurado, abre pra pagamento o(s) evento(s) mensal(is)
 * ainda OPEN do grupo e já dispara o alerta de cobrança — os mesmos dois
 * passos que o admin faria manualmente ("Abrir para pagamento" +
 * "Enviar alerta de cobrança"). O fechamento DEFINITIVO continua manual,
 * só acontece quando os pagamentos são de fato confirmados.
 *
 * Roda uma vez por dia, 12:00 UTC (09:00 no horário de Brasília, sem
 * horário de verão) - horário arbitrário, só precisa ser depois da
 * meia-noite no fuso de quem usa o app pra não comparar o dia errado.
 * Depende do keep-alive (ver {@link KeepAliveScheduler}) manter o serviço
 * de pé no Render; sem isso, um agendamento diário não é confiável num
 * plano que hiberna por inatividade.
 */
@Component
public class GroupAutoSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(GroupAutoSettlementScheduler.class);

    private final GroupJpaRepository groups;
    private final EventUseCase events;
    private final NotificationUseCase notifications;

    public GroupAutoSettlementScheduler(
            GroupJpaRepository groups,
            EventUseCase events,
            NotificationUseCase notifications
    ) {
        this.groups = groups;
        this.events = events;
        this.notifications = notifications;
    }

    @Scheduled(cron = "0 0 12 * * *")
    public void run() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (GroupJpaEntity group : groups.findByActiveTrueAndAutoSettlementDayIsNotNull()) {
            int effectiveDay = Math.min(group.getAutoSettlementDay(), today.lengthOfMonth());
            if (today.getDayOfMonth() != effectiveDay) {
                continue;
            }
            try {
                processGroup(group);
            } catch (Exception exception) {
                log.warn("Fechamento automatico falhou pro grupo {}: {}", group.getId(), exception.getMessage());
            }
        }
    }

    // Cada chamada abaixo (autoStartSettlementForGroup, sendBillingAlertCore)
    // já é transacional por conta própria - sem @Transactional aqui porque
    // chamada direta dentro da mesma classe não passa pelo proxy do Spring.
    public void processGroup(GroupJpaEntity group) {
        List<EventJpaEntity> started = events.autoStartSettlementForGroup(group);
        if (started.isEmpty()) {
            return;
        }
        for (EventJpaEntity event : started) {
            try {
                notifications.sendBillingAlertCore(event, group.getCreatedBy());
            } catch (DomainException ignored) {
                // Sem pagamento pendente pra cobrar nesse evento especifico - normal.
            }
        }
    }
}
