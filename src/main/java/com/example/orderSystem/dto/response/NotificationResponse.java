package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.enums.NotificationType;

import java.time.LocalDateTime;

public record NotificationResponse(String notificationId, NotificationType type, String orderId,
                                   String content, LocalDateTime createdAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getNotificationId(), n.getType(), n.getOrderId(),
                n.getContent(), n.getCreatedAt());
    }
}
