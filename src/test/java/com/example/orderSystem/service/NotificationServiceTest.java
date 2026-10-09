package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.BalanceMessage;
import com.example.orderSystem.dto.websocket.SettlementMessage;
import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @InjectMocks
    private NotificationService notificationService;

    @Mock
    private NotificationPublisher publisher;

    @Test
    @DisplayName("sendBalanceUpdate → publish 到 /queue/balance")
    void sendBalanceUpdate() {
        notificationService.sendBalanceUpdate("alice", 4840, "下單：珍珠奶茶(大) x1");

        ArgumentCaptor<BalanceMessage> captor = ArgumentCaptor.forClass(BalanceMessage.class);
        verify(publisher).publish(eq("alice"), eq("/queue/balance"), captor.capture());

        BalanceMessage msg = captor.getValue();
        assertThat(msg.getAvailableBalance()).isEqualTo(4840);
        assertThat(msg.getReason()).isEqualTo("下單：珍珠奶茶(大) x1");
    }

    @ParameterizedTest
    @CsvSource({
            "SETTLEMENT_SUCCEEDED, SETTLED",
            "SETTLEMENT_SUCCEEDED_OWNER, SETTLED",
            "SETTLEMENT_INSUFFICIENT, FAILED",
            "SETTLEMENT_BLOCKED, FAILED",
            "SETTLEMENT_FAILED_OWNER, FAILED",
            "SETTLEMENT_ABANDONED, ERROR"
    })
    @DisplayName("pushSettlement → 推給收件人,帶 notificationId 與通知內容,result 依類型對應")
    void pushSettlement(NotificationType type, String expectedResult) {
        Notification n = new Notification();
        n.setNotificationId("ntf-1");
        n.setUserId("alice");
        n.setOrderId("ord-001");
        n.setType(type);
        n.setContent("「午餐團」通知內容");

        notificationService.pushSettlement(n);

        ArgumentCaptor<SettlementMessage> captor = ArgumentCaptor.forClass(SettlementMessage.class);
        verify(publisher).publish(eq("alice"), eq("/queue/notification"), captor.capture());

        SettlementMessage msg = captor.getValue();
        assertThat(msg.getNotificationId()).isEqualTo("ntf-1");
        assertThat(msg.getOrderId()).isEqualTo("ord-001");
        assertThat(msg.getResult()).isEqualTo(expectedResult);
        assertThat(msg.getDetail()).isEqualTo("「午餐團」通知內容");
    }
}
