package com.splitbill.application.event;

import java.util.UUID;

/**
 * Publicado quando uma notificacao in-app e gravada. O push pro celular
 * ({@link com.splitbill.infrastructure.push.PushNotificationSender}) so sai
 * depois do commit - se a transacao falhar, ninguem recebe push de algo
 * que nao existe.
 */
public record NotificationCreatedEvent(UUID notificationId, UUID recipientId, String message, String type) {
}
