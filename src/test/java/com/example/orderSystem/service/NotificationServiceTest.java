package com.example.orderSystem.service;

import com.example.orderSystem.dto.websocket.BalanceMessage;
import com.example.orderSystem.dto.websocket.SettlementMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    @Test
    @DisplayName("sendSettlementSuccess → publish SETTLED 通知")
    void sendSettlementSuccess() {
        notificationService.sendSettlementSuccess("alice", "ord-001", "午餐團", 105, 4895);

        ArgumentCaptor<SettlementMessage> captor = ArgumentCaptor.forClass(SettlementMessage.class);
        verify(publisher).publish(eq("alice"), eq("/queue/notification"), captor.capture());

        SettlementMessage msg = captor.getValue();
        assertThat(msg.getResult()).isEqualTo("SETTLED");
        assertThat(msg.getAmount()).isEqualTo(-105);
        assertThat(msg.getBalance()).isEqualTo(4895);
    }

    @Test
    @DisplayName("sendSettlementFailed → publish FAILED 通知")
    void sendSettlementFailed() {
        notificationService.sendSettlementFailed("alice", "ord-001", "午餐團");

        ArgumentCaptor<SettlementMessage> captor = ArgumentCaptor.forClass(SettlementMessage.class);
        verify(publisher).publish(eq("alice"), eq("/queue/notification"), captor.capture());

        SettlementMessage msg = captor.getValue();
        assertThat(msg.getResult()).isEqualTo("FAILED");
        assertThat(msg.getDetail()).contains("餘額不足");
    }
}
