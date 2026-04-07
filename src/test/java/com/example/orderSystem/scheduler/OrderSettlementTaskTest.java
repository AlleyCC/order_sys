package com.example.orderSystem.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class OrderSettlementTaskTest {

    @Test
    @DisplayName("未來的 deadline → delay > 0")
    void futureDeadline() {
        OrderSettlementTask task = new OrderSettlementTask("ord-001", LocalDateTime.now().plusMinutes(10));
        assertThat(task.getDelay(TimeUnit.SECONDS)).isGreaterThan(0);
    }

    @Test
    @DisplayName("過去的 deadline → delay <= 0")
    void pastDeadline() {
        OrderSettlementTask task = new OrderSettlementTask("ord-001", LocalDateTime.now().minusMinutes(1));
        assertThat(task.getDelay(TimeUnit.SECONDS)).isLessThanOrEqualTo(0);
    }

    @Test
    @DisplayName("相同 orderId → equals true")
    void equalsByOrderId() {
        OrderSettlementTask a = new OrderSettlementTask("ord-001", LocalDateTime.now());
        OrderSettlementTask b = new OrderSettlementTask("ord-001", LocalDateTime.now().plusHours(1));
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("不同 orderId → equals false")
    void notEqualsDifferentId() {
        OrderSettlementTask a = new OrderSettlementTask("ord-001", LocalDateTime.now());
        OrderSettlementTask b = new OrderSettlementTask("ord-002", LocalDateTime.now());
        assertThat(a).isNotEqualTo(b);
    }
}
