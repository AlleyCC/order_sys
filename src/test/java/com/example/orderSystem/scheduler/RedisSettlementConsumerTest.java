package com.example.orderSystem.scheduler;

import com.example.orderSystem.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisSettlementConsumerTest {

    @Mock
    private RedisSettlementQueue settlementQueue;

    @Mock
    private OrderService orderService;

    @InjectMocks
    private RedisSettlementConsumer consumer;

    @Test
    @DisplayName("poll 到到期訂單 → remove 成功 → 呼叫 settleOrder")
    void pollClaimAndSettle() {
        Set<String> dueOrders = new LinkedHashSet<>();
        dueOrders.add("ord-001");
        dueOrders.add("ord-002");

        when(settlementQueue.pollDueOrders(anyLong())).thenReturn(dueOrders);
        when(settlementQueue.remove("ord-001")).thenReturn(true);
        when(settlementQueue.remove("ord-002")).thenReturn(true);

        consumer.pollAndSettle();

        verify(orderService).settleOrder("ord-001");
        verify(orderService).settleOrder("ord-002");
    }

    @Test
    @DisplayName("remove 回傳 false（被其他實例搶走）→ 不呼叫 settleOrder")
    void alreadyClaimedByOther() {
        when(settlementQueue.pollDueOrders(anyLong())).thenReturn(Set.of("ord-003"));
        when(settlementQueue.remove("ord-003")).thenReturn(false);

        consumer.pollAndSettle();

        verify(orderService, never()).settleOrder("ord-003");
    }

    @Test
    @DisplayName("沒有到期訂單 → 不做任何事")
    void noDueOrders() {
        when(settlementQueue.pollDueOrders(anyLong())).thenReturn(Collections.emptySet());

        consumer.pollAndSettle();

        verify(orderService, never()).settleOrder(any());
    }

    @Test
    @DisplayName("settleOrder 拋例外 → 重新入隊")
    void settleFailsReEnqueue() {
        when(settlementQueue.pollDueOrders(anyLong())).thenReturn(Set.of("ord-fail"));
        when(settlementQueue.remove("ord-fail")).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(orderService).settleOrder("ord-fail");

        consumer.pollAndSettle();

        verify(settlementQueue).reEnqueue("ord-fail");
    }

    @Test
    @DisplayName("settleOrder 拋例外 → 不影響後續訂單處理")
    void failureDoesNotBlockOthers() {
        Set<String> dueOrders = new LinkedHashSet<>();
        dueOrders.add("ord-fail");
        dueOrders.add("ord-ok");

        when(settlementQueue.pollDueOrders(anyLong())).thenReturn(dueOrders);
        when(settlementQueue.remove("ord-fail")).thenReturn(true);
        when(settlementQueue.remove("ord-ok")).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(orderService).settleOrder("ord-fail");

        consumer.pollAndSettle();

        verify(settlementQueue).reEnqueue("ord-fail");
        verify(orderService).settleOrder("ord-ok");
    }
}
