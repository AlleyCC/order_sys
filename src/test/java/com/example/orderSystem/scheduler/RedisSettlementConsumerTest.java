package com.example.orderSystem.scheduler;

import com.example.orderSystem.scheduler.RedisSettlementQueue.ClaimedBatch;
import com.example.orderSystem.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)   // 每個案例都共用 @BeforeEach 的「沒有到期訂單」預設
class RedisSettlementConsumerTest {

    private static final long LEASE = 1_800_000_060_000L;

    @Mock
    private RedisSettlementQueue settlementQueue;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private RedisSettlementConsumer consumer;

    @BeforeEach
    void noDueOrdersByDefault() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch());
    }

    // ========== 認領與結算 ==========

    @Test
    @DisplayName("認領到的訂單 → settleOrder → 正常結束後以認領時的租約 ack")
    void claimSettleAndAck() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch("ord-001", "ord-002"));

        consumer.pollAndSettle();

        verify(orderService).settleOrder("ord-001");
        verify(orderService).settleOrder("ord-002");
        verify(settlementQueue).ack("ord-001", LEASE);
        verify(settlementQueue).ack("ord-002", LEASE);
    }

    @Test
    @DisplayName("只結算自己認領到的訂單(被其他實例認領的不會出現在結果裡)")
    void settlesOnlyClaimedOrders() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch("ord-001"));

        consumer.pollAndSettle();

        verify(orderService, times(1)).settleOrder(any());
        verify(orderService).settleOrder("ord-001");
    }

    @Test
    @DisplayName("沒有到期訂單 → 不做任何事")
    void noDueOrders() {
        consumer.pollAndSettle();

        verify(orderService, never()).settleOrder(any());
    }

    @Test
    @DisplayName("回收在認領之前")
    void reclaimBeforeClaim() {
        consumer.pollAndSettle();

        InOrder inOrder = inOrder(settlementQueue);
        inOrder.verify(settlementQueue).reclaimExpired(anyLong());
        inOrder.verify(settlementQueue).claimDue(anyLong());
    }

    // ========== 結算失敗與確認失敗 ==========

    @Test
    @DisplayName("settleOrder 拋例外 → 以認領時的租約 reEnqueue,不 ack")
    void settleFailsReEnqueuesWithoutAck() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch("ord-fail"));
        doThrow(new RuntimeException("DB error")).when(orderService).settleOrder("ord-fail");

        consumer.pollAndSettle();

        verify(settlementQueue).reEnqueue("ord-fail", LEASE);
        verify(settlementQueue, never()).ack(any(), anyLong());
    }

    @Test
    @DisplayName("settleOrder 拋例外 → 不影響同批後續訂單")
    void failureDoesNotBlockOthers() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch("ord-fail", "ord-ok"));
        doThrow(new RuntimeException("DB error")).when(orderService).settleOrder("ord-fail");

        consumer.pollAndSettle();

        verify(settlementQueue).reEnqueue("ord-fail", LEASE);
        verify(orderService).settleOrder("ord-ok");
        verify(settlementQueue).ack("ord-ok", LEASE);
    }

    @Test
    @DisplayName("結算成功但 ack 失敗 → 不當成結算失敗(不 reEnqueue),同批後續照常處理")
    void ackFailureIsNotSettlementFailure() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch("ord-001", "ord-002"));
        doThrow(new RuntimeException("Redis timeout")).when(settlementQueue).ack("ord-001", LEASE);

        consumer.pollAndSettle();

        verify(settlementQueue, never()).reEnqueue(any(), anyLong());
        verify(orderService).settleOrder("ord-002");
        verify(settlementQueue).ack("ord-002", LEASE);
    }

    @Test
    @DisplayName("reEnqueue 失敗 → 同批後續照常處理(該筆留在 processing,等租約到期回收)")
    void reEnqueueFailureDoesNotBlockOthers() {
        when(settlementQueue.claimDue(anyLong())).thenReturn(batch("ord-fail", "ord-ok"));
        doThrow(new RuntimeException("DB error")).when(orderService).settleOrder("ord-fail");
        doThrow(new RuntimeException("Redis timeout")).when(settlementQueue).reEnqueue("ord-fail", LEASE);

        consumer.pollAndSettle();

        verify(orderService).settleOrder("ord-ok");
        verify(settlementQueue).ack("ord-ok", LEASE);
    }

    // ========== 重試用完的通知 ==========

    @Test
    @DisplayName("待通知集合裡的訂單 → 搶到的才通知")
    void notifiesClaimedAbandonedOrders() {
        when(settlementQueue.abandonedOrders()).thenReturn(List.of("ord-a", "ord-b"));
        when(settlementQueue.claimAbandoned("ord-a")).thenReturn(true);
        when(settlementQueue.claimAbandoned("ord-b")).thenReturn(false);   // 別台已經在通知

        consumer.pollAndSettle();

        verify(orderService).notifySettlementAbandoned("ord-a");
        verify(orderService, never()).notifySettlementAbandoned("ord-b");
    }

    @Test
    @DisplayName("通知失敗 → 放回待通知集合,下一輪再試;其餘訂單照常通知")
    void failedNotificationIsPutBack() {
        when(settlementQueue.abandonedOrders()).thenReturn(List.of("ord-a", "ord-b"));
        when(settlementQueue.claimAbandoned(any())).thenReturn(true);
        doThrow(new RuntimeException("DB down")).when(orderService).notifySettlementAbandoned("ord-a");

        consumer.pollAndSettle();

        verify(settlementQueue).markAbandoned("ord-a");
        verify(orderService).notifySettlementAbandoned("ord-b");
        verify(settlementQueue, never()).markAbandoned("ord-b");
    }

    private static ClaimedBatch batch(String... orderIds) {
        return new ClaimedBatch(List.of(orderIds), LEASE);
    }
}
