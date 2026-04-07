package com.example.orderSystem.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class OrderSettlementQueueTest {

    @Test
    @DisplayName("add → size 增加")
    void addIncreasesSize() {
        OrderSettlementQueue queue = new OrderSettlementQueue();
        queue.add("ord-001", LocalDateTime.now().plusHours(1));
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("add + remove → size 歸零")
    void removeDecreasesSize() {
        OrderSettlementQueue queue = new OrderSettlementQueue();
        queue.add("ord-001", LocalDateTime.now().plusHours(1));
        queue.remove("ord-001");
        assertThat(queue.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("remove 不存在的 → 不報錯")
    void removeNonexistent() {
        OrderSettlementQueue queue = new OrderSettlementQueue();
        assertThatCode(() -> queue.remove("nonexistent")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("過期任務可立即 take")
    void expiredTaskAvailable() throws InterruptedException {
        OrderSettlementQueue queue = new OrderSettlementQueue();
        queue.add("ord-001", LocalDateTime.now().minusSeconds(1));

        // Should be available immediately
        OrderSettlementTask task = queue.take();
        assertThat(task.getOrderId()).isEqualTo("ord-001");
    }
}
