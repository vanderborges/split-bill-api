package com.splitbill.adapters.input.rest;

import com.splitbill.application.dto.NotificationResponse;
import com.splitbill.application.usecase.NotificationUseCase;
import com.splitbill.infrastructure.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationUseCase notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationUseCase notifications, CurrentUser currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<NotificationResponse> list() {
        return notifications.list(currentUser.id());
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.unreadCount(currentUser.id()));
    }

    @PutMapping("/{id}/read")
    public void markAsRead(@PathVariable UUID id) {
        notifications.markAsRead(id, currentUser.id());
    }
}
