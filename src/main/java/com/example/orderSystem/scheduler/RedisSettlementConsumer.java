package com.example.orderSystem.scheduler;

import com.example.orderSystem.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisSettlementConsumer {

    private final RedisSettlementQueue settlementQueue;
    private final OrderService orderService;

    @Scheduled(fixedRate = 1000)
    public void pollAndSettle() {
        Set<String> dueOrders = settlementQueue.pollDueOrders(System.currentTimeMillis());
        for (String orderId : dueOrders) {
            if (settlementQueue.remove(orderId)) {
                log.info("Deadline reached for order {}, starting settlement", orderId);
                try {
                    orderService.settleOrder(orderId);
                } catch (Exception e) {
                    log.error("Settlement failed for order {}, re-enqueuing", orderId, e);
                    // Re-enqueue with score=now so it retries on next poll
                    settlementQueue.reEnqueue(orderId);
                }
            }
        }
    }
}
