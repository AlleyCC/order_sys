package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.BalanceMessage;
import com.example.orderSystem.dto.websocket.SettlementMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationPublisher publisher;

    public void sendBalanceUpdate(String userId, long availableBalance, String reason) {
        publisher.publish(userId, "/queue/balance", new BalanceMessage(availableBalance, reason));
    }

    public void sendSettlementSuccess(String userId, String orderId, String orderName,
                                      int amount, long balance) {
        publisher.publish(userId, "/queue/notification",
                SettlementMessage.builder()
                        .orderId(orderId)
                        .orderName(orderName)
                        .result("SETTLED")
                        .amount(-amount)
                        .balance(balance)
                        .build());
    }

    public void sendSettlementFailed(String userId, String orderId, String orderName) {
        publisher.publish(userId, "/queue/notification",
                SettlementMessage.builder()
                        .orderId(orderId)
                        .orderName(orderName)
                        .result("FAILED")
                        .detail("餘額不足，請儲值後聯繫團主重新結算")
                        .build());
    }
}
