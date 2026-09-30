package com.example.orderSystem.scheduler;

import com.example.orderSystem.scheduler.RedisSettlementQueue.ClaimedBatch;
import com.example.orderSystem.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
        // 先回收:認領後消失的實例留下的訂單放回 queue;次數用完的進待通知集合
        settlementQueue.reclaimExpired(System.currentTimeMillis());

        ClaimedBatch batch = settlementQueue.claimDue(System.currentTimeMillis());
        for (String orderId : batch.orderIds()) {
            boolean settled = settle(orderId);
            try {
                if (settled) {
                    settlementQueue.ack(orderId, batch.leaseUntil());
                } else {
                    // 間隔逐次拉長;次數用完就停止自動重試,進待通知集合
                    settlementQueue.reEnqueue(orderId, batch.leaseUntil());
                }
            } catch (Exception e) {
                // 訂單仍在 processing,租約到期後會被回收重跑;不影響同批其他訂單
                log.error("Failed to release order {} from processing, it will be reclaimed after the lease expires",
                        orderId, e);
            }
        }

        notifyAbandoned();
    }

    /** @return settleOrder 是否正常結束 */
    private boolean settle(String orderId) {
        log.info("Deadline reached for order {}, starting settlement", orderId);
        try {
            orderService.settleOrder(orderId);
            return true;
        } catch (Exception e) {
            log.error("Settlement failed for order {}, re-enqueuing", orderId, e);
            return false;
        }
    }

    /** 通知團主與管理員手動處理;通知失敗就放回,下一輪再試 */
    private void notifyAbandoned() {
        for (String orderId : settlementQueue.abandonedOrders()) {
            if (!settlementQueue.claimAbandoned(orderId)) {
                continue;   // 別台已經在通知
            }
            try {
                orderService.notifySettlementAbandoned(orderId);
            } catch (Exception e) {
                log.error("Failed to notify abandoned settlement for order {}, will retry", orderId, e);
                settlementQueue.markAbandoned(orderId);
            }
        }
    }
}
