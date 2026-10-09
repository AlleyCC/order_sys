package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.BalanceMessage;
import com.example.orderSystem.dto.websocket.SettlementMessage;
import com.example.orderSystem.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationPublisher publisher;

    public void sendBalanceUpdate(String userId, long availableBalance, String reason) {
        publisher.publish(userId, "/queue/balance", new BalanceMessage(availableBalance, reason));
    }

    /** 推播一則已保存的結算通知;只負責盡力送達,收件人離線時由查詢未讀補齊 */
    public void pushSettlement(Notification notification) {
        publisher.publish(notification.getUserId(), "/queue/notification",
                SettlementMessage.builder()
                        .notificationId(notification.getNotificationId())
                        .orderId(notification.getOrderId())
                        .result(resultOf(notification))
                        .detail(notification.getContent())
                        .build());
    }

    private static String resultOf(Notification notification) {
        return switch (notification.getType()) {
            case SETTLEMENT_SUCCEEDED, SETTLEMENT_SUCCEEDED_OWNER -> "SETTLED";
            case SETTLEMENT_INSUFFICIENT, SETTLEMENT_BLOCKED, SETTLEMENT_FAILED_OWNER -> "FAILED";
            case SETTLEMENT_ABANDONED -> "ERROR";
        };
    }
}
