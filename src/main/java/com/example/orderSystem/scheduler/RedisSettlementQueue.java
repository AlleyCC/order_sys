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

    public void reEnqueue(String orderId) {
        double score = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(KEY, orderId, score);
        log.info("Re-enqueued settlement for order {} (retry)", orderId);
    }

    public Set<String> pollDueOrders(long nowEpochMs) {
        Set<String> results = redisTemplate.opsForZSet().rangeByScore(KEY, 0, nowEpochMs, 0, 10);
        return results != null ? results : Collections.emptySet();
    }
}
