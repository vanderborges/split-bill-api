package com.splitbill.infrastructure.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.splitbill.application.event.NotificationCreatedEvent;
import com.splitbill.infrastructure.persistence.entity.DeviceTokenJpaEntity;
import com.splitbill.infrastructure.persistence.repository.DeviceTokenJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Manda push pros aparelhos de quem recebeu uma notificacao in-app.
 * Roda depois do commit e fora da thread da requisicao (@Async): a
 * cobranca nao fica esperando o Firebase, e falha de push nunca desfaz a
 * notificacao gravada.
 */
@Component
public class PushNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationSender.class);
    private static final int MAX_BODY = 300;
    // Token que o Firebase diz que nao existe mais (app desinstalado,
    // token trocado): sai da tabela pra nao tentar de novo.
    private static final Set<MessagingErrorCode> DEAD_TOKEN = Set.of(
            MessagingErrorCode.UNREGISTERED,
            MessagingErrorCode.INVALID_ARGUMENT,
            MessagingErrorCode.SENDER_ID_MISMATCH);

    private final FirebaseMessagingProvider firebase;
    private final DeviceTokenJpaRepository devices;

    public PushNotificationSender(FirebaseMessagingProvider firebase, DeviceTokenJpaRepository devices) {
        this.firebase = firebase;
        this.devices = devices;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        Optional<FirebaseMessaging> messaging = firebase.get();
        if (messaging.isEmpty()) {
            return;
        }
        List<String> tokens = devices.findByUserId(event.recipientId()).stream()
                .map(DeviceTokenJpaEntity::getToken)
                .toList();
        if (tokens.isEmpty()) {
            return;
        }
        String body = event.message().length() > MAX_BODY
                ? event.message().substring(0, MAX_BODY - 1) + "…"
                : event.message();
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder()
                        .setTitle("DividiAí")
                        .setBody(body)
                        .build())
                // O app usa pra abrir a tela de Notificacoes ao tocar.
                .putData("route", "/notifications")
                .putData("notificationId", event.notificationId().toString())
                .putData("type", event.type())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .setNotification(AndroidNotification.builder()
                                .setChannelId("dividiai_notifications")
                                .build())
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();
        try {
            BatchResponse response = messaging.get().sendEachForMulticast(message);
            List<String> dead = new ArrayList<>();
            for (int i = 0; i < response.getResponses().size(); i++) {
                FirebaseMessagingException error = response.getResponses().get(i).getException();
                if (error != null && DEAD_TOKEN.contains(error.getMessagingErrorCode())) {
                    dead.add(tokens.get(i));
                } else if (error != null) {
                    log.warn("Push falhou pra um aparelho: {} ({})", error.getMessagingErrorCode(), error.getMessage());
                }
            }
            if (!dead.isEmpty()) {
                devices.deleteByTokenIn(dead);
            }
            log.info("Push: {} enviado(s), {} token(s) invalido(s) removido(s).", response.getSuccessCount(), dead.size());
        } catch (FirebaseMessagingException | RuntimeException error) {
            log.error("Push falhou: {}", error.getMessage());
        }
    }
}
