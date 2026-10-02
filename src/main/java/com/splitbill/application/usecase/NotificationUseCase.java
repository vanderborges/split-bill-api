package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.NotificationResponse;
import com.splitbill.application.dto.PaymentSuggestionResponse;
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

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class NotificationUseCase {

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private final NotificationJpaRepository notifications;
    private final EventJpaRepository events;
    private final UserJpaRepository users;
    private final EventSettlementUseCase settlements;
    private final GroupUseCase groupRules;
    private final MonthlyReportUseCase reports;

    public NotificationUseCase(
            NotificationJpaRepository notifications,
            EventJpaRepository events,
            UserJpaRepository users,
            EventSettlementUseCase settlements,
            GroupUseCase groupRules,
            MonthlyReportUseCase reports
    ) {
        this.notifications = notifications;
        this.events = events;
        this.users = users;
        this.settlements = settlements;
        this.groupRules = groupRules;
        this.reports = reports;
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
            notification.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    /**
     * Alerta de cobrança: manda uma notificação personalizada pra quem ainda
     * está devendo (settlement DEBTOR + PENDING) nesse evento, dizendo
     * quanto e pra quem pagar - reaproveita {@link MonthlyReportUseCase#getPaymentSuggestions},
     * que já resolve tanto o caso de recebedor eleito pro grupo (todo
     * devedor manda pra essa pessoa) quanto o casamento devedor/credor
     * padrão (sem recebedor eleito), incluindo chave PIX e nome completo
     * de quem recebe.
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

        List<PaymentSuggestionResponse> suggestions = reports.getPaymentSuggestions(eventId, requesterId);
        // Guardado em UTC explicitamente (ver nota em toResponse) - o app
        // converte pro horário local do aparelho na hora de exibir.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        for (EventSettlementResponse settlement : pending) {
            UserJpaEntity recipient = users.findById(settlement.userId())
                    .orElseThrow(() -> new DomainException("User not found"));
            BillingMessage billingMessage = buildBillingMessage(event, settlement, suggestions);
            NotificationJpaEntity notification = new NotificationJpaEntity();
            notification.setId(UUID.randomUUID());
            notification.setRecipient(recipient);
            notification.setEvent(event);
            notification.setMessage(billingMessage.text());
            notification.setPixKey(billingMessage.pixKey());
            notification.setReceiverName(billingMessage.receiverName());
            notification.setCreatedBy(admin);
            notification.setCreatedAt(now);
            notifications.save(notification);
        }

        return pending.size();
    }

    /**
     * Texto da notificação, mais a chave PIX e nome de quem recebe quando
     * há um único destinatário claro - usados pelo app pra mostrar um
     * botão de copiar o PIX sem precisar reextrair do texto livre. Com
     * mais de um destinatário (sem recebedor eleito, casando com vários
     * credores), não há um único PIX pra copiar, então ficam nulos.
     */
    private record BillingMessage(String text, String pixKey, String receiverName) {
    }

    private BillingMessage buildBillingMessage(
            EventJpaEntity event,
            EventSettlementResponse settlement,
            List<PaymentSuggestionResponse> suggestions
    ) {
        List<PaymentSuggestionResponse> targets = suggestions.stream()
                .filter(suggestion -> suggestion.fromUserId().equals(settlement.userId()))
                .toList();

        StringBuilder message = new StringBuilder("DividiAí: no evento ")
                .append(event.getName())
                .append(" você tem ")
                .append(formatCurrency(settlement.amount()))
                .append(" pendente. ");

        if (targets.isEmpty()) {
            message.append("Combine com o grupo pra quem enviar o pagamento.");
            return new BillingMessage(message.toString(), null, null);
        }

        List<String> parts = new ArrayList<>();
        List<UserJpaEntity> receivers = new ArrayList<>();
        for (PaymentSuggestionResponse target : targets) {
            UserJpaEntity receiver = users.findById(target.toUserId())
                    .orElseThrow(() -> new DomainException("User not found"));
            receivers.add(receiver);
            String pixKey = receiver.getPixKey();
            String part = "envie " + formatCurrency(target.amount()) + " para " + receiver.getFullName();
            if (pixKey != null && !pixKey.isBlank()) {
                part += " (PIX: " + pixKey + ")";
            }
            parts.add(part);
        }
        message.append(String.join("; ", parts)).append(".");

        if (receivers.size() == 1) {
            UserJpaEntity receiver = receivers.get(0);
            String pixKey = receiver.getPixKey();
            return new BillingMessage(
                    message.toString(),
                    pixKey == null || pixKey.isBlank() ? null : pixKey,
                    receiver.getFullName()
            );
        }
        return new BillingMessage(message.toString(), null, null);
    }

    private String formatCurrency(java.math.BigDecimal amount) {
        NumberFormat currencyFormat = NumberFormat.getCurrencyInstance(PT_BR);
        return currencyFormat.format(amount).replace(' ', ' ');
    }

    private NotificationResponse toResponse(NotificationJpaEntity notification) {
        EventJpaEntity event = notification.getEvent();
        return new NotificationResponse(
                notification.getId(),
                event == null ? null : event.getId(),
                event == null ? null : event.getName(),
                notification.getMessage(),
                // createdAt/readAt sao gravados como LocalDateTime em UTC
                // (ver sendBillingAlert) mas sem marcacao explicita de fuso -
                // convertemos pra Instant aqui so na resposta, pra o app
                // (que interpreta string sem fuso como hora local) exibir
                // certo em qualquer fuso do aparelho, nao so no de quem
                // disparou o alerta.
                notification.getCreatedAt() == null
                        ? null
                        : notification.getCreatedAt().toInstant(ZoneOffset.UTC),
                notification.getReadAt() == null
                        ? null
                        : notification.getReadAt().toInstant(ZoneOffset.UTC),
                notification.getPixKey(),
                notification.getReceiverName()
        );
    }
}
