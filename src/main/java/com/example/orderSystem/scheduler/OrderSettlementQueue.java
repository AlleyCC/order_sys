package com.example.orderSystem.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.DelayQueue;

@Slf4j
@Component
public class OrderSettlementQueue {

    private final DelayQueue<OrderSettlementTask> queue = new DelayQueue<>();

    public void add(String orderId, LocalDateTime deadline) {
        OrderSettlementTask task = new OrderSettlementTask(orderId, deadline);
        queue.put(task);
        log.info("Scheduled settlement for order {} at {}", orderId, deadline);
    }

    public void remove(String orderId) {
        boolean removed = queue.remove(new OrderSettlementTask(orderId, LocalDateTime.now()));
        if (removed) {
            log.info("Removed settlement task for order {}", orderId);
        }
    }

    public OrderSettlementTask take() throws InterruptedException {
        return queue.take();
    }

    public int size() {
        return queue.size();
    }
}
