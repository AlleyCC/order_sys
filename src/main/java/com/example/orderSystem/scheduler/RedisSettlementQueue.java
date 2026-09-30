package com.example.orderSystem.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSettlementQueue {

    private static final String KEY = "order:settlement:queue";
    private static final String RETRY_KEY = "order:settlement:retries";

    /** 第 n 次重試前要等的時間;用完就放棄,交給人工處理 */
    private static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000};

    private final StringRedisTemplate redisTemplate;

    public void add(String orderId, LocalDateTime deadline) {
        double score = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        redisTemplate.opsForZSet().add(KEY, orderId, score);
        log.info("Scheduled settlement for order {} at {}", orderId, deadline);
    }

    public boolean remove(String orderId) {
        Long removed = redisTemplate.opsForZSet().remove(KEY, orderId);
        boolean success = removed != null && removed > 0;
        if (success) {
            log.info("Removed settlement task for order {}", orderId);
        }
        return success;
    }

    /**
     * 排入下一次重試,間隔依 RETRY_DELAYS_MS 拉長。
     * @return false 表示重試次數已用完,沒有再入隊
     */
    public boolean reEnqueue(String orderId) {
        Long attempt = redisTemplate.opsForHash().increment(RETRY_KEY, orderId, 1);
        if (attempt == null || attempt > RETRY_DELAYS_MS.length) {
            clearRetries(orderId);
            log.warn("Giving up settlement for order {} after {} retries", orderId, RETRY_DELAYS_MS.length);
            return false;
        }
        long delay = RETRY_DELAYS_MS[attempt.intValue() - 1];
        double score = System.currentTimeMillis() + delay;
        redisTemplate.opsForZSet().add(KEY, orderId, score);
        log.info("Re-enqueued settlement for order {} (retry {} in {} ms)", orderId, attempt, delay);
        return true;
    }

    public void clearRetries(String orderId) {
        redisTemplate.opsForHash().delete(RETRY_KEY, orderId);
    }

    public Set<String> pollDueOrders(long nowEpochMs) {
        Set<String> results = redisTemplate.opsForZSet().rangeByScore(KEY, 0, nowEpochMs, 0, 10);
        return results != null ? results : Collections.emptySet();
    }
}
