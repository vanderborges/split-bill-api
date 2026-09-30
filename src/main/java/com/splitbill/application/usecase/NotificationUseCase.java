package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.NotificationResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.SettlementRole;
import com.splitbill.domain.valueobject.SettlementStatus;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.NotificationJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.NotificationJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationUseCase {

    private final NotificationJpaRepository notifications;
    private final EventJpaRepository events;
    private final UserJpaRepository users;
    private final EventSettlementUseCase settlements;
    private final GroupUseCase groupRules;

    public NotificationUseCase(
            NotificationJpaRepository notifications,
            EventJpaRepository events,
            UserJpaRepository users,
            EventSettlementUseCase settlements,
            GroupUseCase groupRules
    ) {
        this.notifications = notifications;
        this.events = events;
        this.users = users;
        this.settlements = settlements;
        this.groupRules = groupRules;
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(UUID userId) {
        return notifications.findByRecipientIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByRecipientIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markAsRead(UUID id, UUID userId) {
        NotificationJpaEntity notification = notifications.findById(id)
                .orElseThrow(() -> new DomainException("Notification not found"));
        if (!notification.getRecipient().getId().equals(userId)) {
            throw new DomainException("Notification not found");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now());
        }
    }

    /**
     * Alerta de cobrança (Etapa 1): manda uma notificação simples pra quem
     * ainda está devendo (settlement DEBTOR + PENDING) nesse evento. Mais
     * pra frente isso evolui pra puxar a chave PIX de quem vai receber
     * (configurável por evento) e montar a mensagem com ela - por enquanto
     * é só um aviso genérico.
     */
    @Transactional
    public int sendBillingAlert(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Event not found"));
        groupRules.requireAdmin(event.getGroup().getId(), requesterId);
        UserJpaEntity admin = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("User not found"));

        List<EventSettlementResponse> pending = settlements.listByEvent(eventId, requesterId).stream()
                .filter(settlement -> settlement.role() == SettlementRole.DEBTOR
                        && settlement.status() == SettlementStatus.PENDING)
                .toList();
        if (pending.isEmpty()) {
            throw new DomainException("Nao ha pagamentos pendentes para cobrar neste evento");
        }

        String message = "Dividi Ai, o Evento " + event.getName()
                + " esta com pagamento aberto. Por favor, enviar pagamento o quanto antes";
        LocalDateTime now = LocalDateTime.now();

        for (EventSettlementResponse settlement : pending) {
            UserJpaEntity recipient = users.findById(settlement.userId())
                    .orElseThrow(() -> new DomainException("User not found"));
            NotificationJpaEntity notification = new NotificationJpaEntity();
            notification.setId(UUID.randomUUID());
            notification.setRecipient(recipient);
            notification.setEvent(event);
            notification.setMessage(message);
            notification.setCreatedBy(admin);
            notification.setCreatedAt(now);
            notifications.save(notification);
        }

        return pending.size();
    }

    private NotificationResponse toResponse(NotificationJpaEntity notification) {
        EventJpaEntity event = notification.getEvent();
        return new NotificationResponse(
                notification.getId(),
                event == null ? null : event.getId(),
                event == null ? null : event.getName(),
                notification.getMessage(),
                notification.getCreatedAt(),
                notification.getReadAt()
        );
    }
}
