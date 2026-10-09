package com.example.orderSystem.scheduler;

import com.example.orderSystem.mapper.OrderItemIdempotencyKeyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期刪除過期的 Idempotency-Key 記錄。超過保存期限後,同一個 key 視為新的下單。
 *
 * 排程由 SchedulingConfig 啟用,不受 app.scheduler.enabled(自動結算的開關)影響。
 * 多台機器同時執行無妨:DELETE 本身冪等,重疊時只是某台刪到 0 筆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyKeyCleanupJob {

    static final int RETENTION_HOURS = 24;
    static final int BATCH_SIZE = 1000;

    private final OrderItemIdempotencyKeyMapper idempotencyKeyMapper;

    @Scheduled(cron = "0 0 * * * *")
    public void purgeExpired() {
        // 分批刪:一次刪大量資料會長時間持有鎖,拖住正在下單的請求
        int total = 0;
        int deleted;
        do {
            deleted = idempotencyKeyMapper.deleteExpired(RETENTION_HOURS, BATCH_SIZE);
            total += deleted;
        } while (deleted == BATCH_SIZE);

        if (total > 0) {
            log.info("Purged {} expired idempotency keys", total);
        }
    }
}
