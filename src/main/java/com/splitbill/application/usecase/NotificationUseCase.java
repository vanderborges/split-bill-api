package com.splitbill.application.usecase;

import com.splitbill.application.dto.EventSettlementResponse;
import com.splitbill.application.dto.MyPaymentResponse;
import com.splitbill.application.dto.UpdateSettlementStatusRequest;
import com.splitbill.application.dto.NotificationResponse;
import com.splitbill.application.dto.PaymentSuggestionResponse;
import com.splitbill.domain.exception.DomainException;
import com.splitbill.domain.valueobject.SettlementRole;
import com.splitbill.domain.valueobject.SettlementStatus;
import com.splitbill.domain.valueobject.EventStatus;
import com.splitbill.domain.valueobject.GroupMemberRole;
import com.splitbill.infrastructure.persistence.entity.EventJpaEntity;
import com.splitbill.infrastructure.persistence.entity.EventSettlementJpaEntity;
import com.splitbill.infrastructure.persistence.entity.GroupMemberJpaEntity;
import com.splitbill.infrastructure.persistence.entity.NotificationJpaEntity;
import com.splitbill.infrastructure.persistence.entity.UserJpaEntity;
import com.splitbill.infrastructure.persistence.repository.EventJpaRepository;
import com.splitbill.infrastructure.persistence.repository.EventSettlementJpaRepository;
import com.splitbill.infrastructure.persistence.repository.GroupMemberJpaRepository;
import com.splitbill.infrastructure.persistence.repository.NotificationJpaRepository;
import com.splitbill.infrastructure.persistence.repository.UserJpaRepository;
import com.splitbill.application.event.NotificationCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;
    private final EventSettlementJpaRepository settlementRows;
    private final GroupMemberJpaRepository members;

    public NotificationUseCase(
            NotificationJpaRepository notifications,
            EventJpaRepository events,
            UserJpaRepository users,
            EventSettlementUseCase settlements,
            GroupUseCase groupRules,
            MonthlyReportUseCase reports,
            ApplicationEventPublisher eventPublisher,
            EventSettlementJpaRepository settlementRows,
            GroupMemberJpaRepository members
    ) {
        this.notifications = notifications;
        this.events = events;
        this.users = users;
        this.settlements = settlements;
        this.groupRules = groupRules;
        this.reports = reports;
        this.eventPublisher = eventPublisher;
        this.settlementRows = settlementRows;
        this.members = members;
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
                .orElseThrow(() -> new DomainException("Notificação não encontrada."));
        if (!notification.getRecipient().getId().equals(userId)) {
            throw new DomainException("Notificação não encontrada.");
        }
        if (notification.getReadAt() == null) {
            notification.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        NotificationJpaEntity notification = notifications.findById(id)
                .orElseThrow(() -> new DomainException("Notificação não encontrada."));
        if (!notification.getRecipient().getId().equals(userId)) {
            throw new DomainException("Notificação não encontrada.");
        }
        notifications.delete(notification);
    }

    @Transactional
    public void clearAll(UUID userId) {
        notifications.deleteByRecipientId(userId);
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
                .orElseThrow(() -> new DomainException("Evento não encontrado."));
        groupRules.requireAdmin(event.getGroup().getId(), requesterId);
        UserJpaEntity admin = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        return sendBillingAlertCore(event, admin);
    }

    /**
     * Mesma lógica de {@link #sendBillingAlert}, mas sem exigir um admin
     * fazendo a requisição - usada pelo
     * {@link com.splitbill.infrastructure.scheduling.GroupAutoSettlementScheduler}
     * logo depois de abrir o evento pra pagamento automaticamente.
     * {@code actingAs} só preenche o campo de autoria da notificação (não
     * aparece pra quem recebe) - usa um admin do grupo como "autor" no
     * lugar de quem literalmente clicou no botão, já que ninguém clicou.
     */
    @Transactional
    public int sendBillingAlertCore(EventJpaEntity event, UserJpaEntity actingAs) {
        UUID eventId = event.getId();
        List<EventSettlementResponse> pending = settlements.listByEvent(eventId, actingAs.getId()).stream()
                .filter(settlement -> settlement.role() == SettlementRole.DEBTOR
                        && settlement.status() == SettlementStatus.PENDING)
                .toList();
        if (pending.isEmpty()) {
            throw new DomainException("Não há pagamentos pendentes para cobrar neste evento.");
        }

        List<PaymentSuggestionResponse> suggestions = reports.getPaymentSuggestions(eventId, actingAs.getId());
        // Guardado em UTC explicitamente (ver nota em toResponse) - o app
        // converte pro horário local do aparelho na hora de exibir.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        int sent = 0;
        for (EventSettlementResponse settlement : pending) {
            UserJpaEntity recipient = users.findById(settlement.userId())
                    .orElseThrow(() -> new DomainException("Usuário não encontrado."));
            if (recipient.isPlaceholder()) {
                // Pessoa sem cadastro nao abre o app: nao ha pra quem mandar.
                continue;
            }
            BillingMessage billingMessage = buildBillingMessage(event, settlement, suggestions);
            NotificationJpaEntity notification = new NotificationJpaEntity();
            notification.setId(UUID.randomUUID());
            notification.setRecipient(recipient);
            notification.setEvent(event);
            notification.setMessage(billingMessage.text());
            notification.setPixKey(billingMessage.pixKey());
            notification.setReceiverName(billingMessage.receiverName());
            notification.setCreatedBy(actingAs);
            notification.setCreatedAt(now);
            notifications.save(notification);
            eventPublisher.publishEvent(new NotificationCreatedEvent(
                    notification.getId(), recipient.getId(), notification.getMessage(), notification.getType()));
            sent++;
        }

        return sent;
    }

    /**
     * Texto da notificação, mais a chave PIX e nome de quem recebe quando
     * há um único destinatário claro - usados pelo app pra mostrar um
     * botão de copiar o PIX sem precisar reextrair do texto livre. Com
     * mais de um destinatário (sem recebedor eleito, casando com vários
     * credores), não há um único PIX pra copiar, então ficam nulos.
     */
    private record BillingMessage(
            String text,
            String pixKey,
            String receiverName,
            String headline,
            List<MyPaymentResponse.Payee> payees
    ) {
    }

    private BillingMessage buildBillingMessage(
            EventJpaEntity event,
            EventSettlementResponse settlement,
            List<PaymentSuggestionResponse> suggestions
    ) {
        List<PaymentSuggestionResponse> targets = suggestions.stream()
                .filter(suggestion -> suggestion.fromUserId().equals(settlement.userId()))
                .toList();

        // Comeca pelo nome do grupo: quem esta em mais de um grupo nao sabia
        // de qual era a cobranca so pelo nome do evento (ex.: "10/2026").
        String headline = event.getGroup().getName()
                + ": no evento " + event.getName()
                + ", você tem " + formatCurrency(settlement.amount()) + " pendente.";
        StringBuilder message = new StringBuilder(headline).append(' ');

        if (targets.isEmpty()) {
            message.append("Combine com o grupo para quem enviar o pagamento.");
            return new BillingMessage(message.toString(), null, null, headline, List.of());
        }

        List<String> parts = new ArrayList<>();
        List<UserJpaEntity> receivers = new ArrayList<>();
        List<MyPaymentResponse.Payee> payees = new ArrayList<>();
        for (PaymentSuggestionResponse target : targets) {
            UserJpaEntity receiver = users.findById(target.toUserId())
                    .orElseThrow(() -> new DomainException("Usuário não encontrado."));
            receivers.add(receiver);
            String pixKey = receiver.getPixKey();
            String part = "envie " + formatCurrency(target.amount()) + " para " + receiver.getFullName();
            if (pixKey != null && !pixKey.isBlank()) {
                part += " (PIX: " + pixKey + ")";
            }
            parts.add(part);
            payees.add(new MyPaymentResponse.Payee(
                    receiver.getFullName(),
                    target.amount(),
                    pixKey == null || pixKey.isBlank() ? null : pixKey));
        }
        String joined = String.join("; ", parts);
        message.append(Character.toUpperCase(joined.charAt(0))).append(joined.substring(1)).append(".");

        if (receivers.size() == 1) {
            UserJpaEntity receiver = receivers.get(0);
            String pixKey = receiver.getPixKey();
            return new BillingMessage(
                    message.toString(),
                    pixKey == null || pixKey.isBlank() ? null : pixKey,
                    receiver.getFullName(),
                    headline,
                    payees
            );
        }
        return new BillingMessage(message.toString(), null, null, headline, payees);
    }

    /**
     * Botao "Pagar" do Relatorio: o acerto de quem esta logado (ou do
     * titular da cobranca vinculada dele) neste evento, com o mesmo texto da
     * notificacao de cobranca.
     */
    @Transactional
    public MyPaymentResponse myPayment(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Evento não encontrado."));
        EventSettlementResponse settlement = payerSettlement(event, requesterId);
        List<PaymentSuggestionResponse> suggestions = reports.getPaymentSuggestions(eventId, requesterId);
        BillingMessage billingMessage = buildBillingMessage(event, settlement, suggestions);
        return new MyPaymentResponse(
                settlement.id(),
                settlement.status(),
                settlement.amount(),
                billingMessage.text(),
                billingMessage.pixKey(),
                billingMessage.receiverName(),
                billingMessage.headline(),
                billingMessage.payees()
        );
    }

    /**
     * "Confirmar pagamento" de quem devia: o acerto fica "pagamento
     * informado" (os vinculados acompanham) e os admins do grupo recebem um
     * aviso pra confirmar ou devolver pra pendente.
     */
    @Transactional
    public MyPaymentResponse informPayment(UUID eventId, UUID requesterId) {
        EventJpaEntity event = events.findById(eventId)
                .orElseThrow(() -> new DomainException("Evento não encontrado."));
        EventSettlementResponse settlement = payerSettlement(event, requesterId);
        if (settlement.status() != SettlementStatus.PENDING) {
            throw new DomainException("Esse pagamento já foi informado.");
        }
        UserJpaEntity payer = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        LocalDateTime now = LocalDateTime.now();
        UUID rootId = settlement.userId();
        settlementRows.findByEventId(eventId).stream()
                .filter(row -> reports.billingRootId(row.getUser()).equals(rootId))
                .forEach(row -> {
                    row.setStatus(SettlementStatus.INFORMED);
                    row.setUpdatedAt(now);
                });

        String text = event.getGroup().getName() + ": " + payer.getNickname()
                + " informou o pagamento de " + formatCurrency(settlement.amount())
                + " no evento " + event.getName() + ". Confirme no evento.";
        members.findByGroupIdAndActiveTrue(event.getGroup().getId()).stream()
                .filter(member -> member.getRole() == GroupMemberRole.ADMIN)
                .map(GroupMemberJpaEntity::getUser)
                .filter(admin -> !admin.isPlaceholder())
                .forEach(admin -> notify(admin, event, text,
                        NotificationJpaEntity.TYPE_PAYMENT_INFORMED, payer));
        return myPayment(eventId, requesterId);
    }

    /**
     * Admin marca pago/pendente. Se a pessoa tinha informado o pagamento,
     * ela recebe um aviso de que foi confirmado ou que voltou pra pendente.
     */
    @Transactional
    public EventSettlementResponse reviewSettlement(
            UUID settlementId, UpdateSettlementStatusRequest request, UUID adminId) {
        EventSettlementJpaEntity row = settlementRows.findById(settlementId)
                .orElseThrow(() -> new DomainException("Pagamento não encontrado."));
        SettlementStatus previous = row.getStatus();
        EventSettlementResponse response = settlements.updateStatus(settlementId, request, adminId);
        if (previous == SettlementStatus.INFORMED && !row.getUser().isPlaceholder()) {
            EventJpaEntity event = row.getEvent();
            String text = event.getGroup().getName() + ": "
                    + (response.status() == SettlementStatus.PAID
                    ? "seu pagamento de " + formatCurrency(response.amount()) + " no evento "
                            + event.getName() + " foi confirmado."
                    : "seu pagamento de " + formatCurrency(response.amount()) + " no evento "
                            + event.getName() + " não foi confirmado e voltou para pendente.");
            UserJpaEntity admin = users.findById(adminId)
                    .orElseThrow(() -> new DomainException("Usuário não encontrado."));
            notify(row.getUser(), event, text, NotificationJpaEntity.TYPE_PAYMENT_REVIEWED, admin);
        }
        return response;
    }

    private EventSettlementResponse payerSettlement(EventJpaEntity event, UUID requesterId) {
        if (event.getStatus() != EventStatus.SETTLING) {
            throw new DomainException("O pagamento só pode ser feito com o evento aguardando pagamento.");
        }
        UserJpaEntity requester = users.findById(requesterId)
                .orElseThrow(() -> new DomainException("Usuário não encontrado."));
        UUID rootId = reports.billingRootId(requester);
        return settlements.listByEvent(event.getId(), requesterId).stream()
                .filter(settlement -> settlement.userId().equals(rootId))
                .filter(settlement -> settlement.role() == SettlementRole.DEBTOR)
                .findFirst()
                .orElseThrow(() -> new DomainException("Você não tem pagamento a fazer neste evento."));
    }

    private void notify(UserJpaEntity recipient, EventJpaEntity event, String text, String type, UserJpaEntity author) {
        NotificationJpaEntity notification = new NotificationJpaEntity();
        notification.setId(UUID.randomUUID());
        notification.setRecipient(recipient);
        notification.setEvent(event);
        notification.setMessage(text.length() > 500 ? text.substring(0, 500) : text);
        notification.setType(type);
        notification.setCreatedBy(author);
        notification.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        notifications.save(notification);
        eventPublisher.publishEvent(new NotificationCreatedEvent(
                notification.getId(), recipient.getId(), notification.getMessage(), notification.getType()));
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
                notification.getReceiverName(),
                notification.getType(),
                event == null ? null : event.getGroup().getId()
        );
    }
}
