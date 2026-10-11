package com.example.orderSystem.config;

import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSetCache;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RedissonBehaviorTest extends AbstractIntegrationTest {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String uniqueName(String prefix) {
        return "spike:" + prefix + ":" + UUID.randomUUID();
    }

    @Test
    @DisplayName("RRateLimiter 在時間窗內超過 rate 次即拒絕")
    void rejectsBeyondRateWithinInterval() {
        RRateLimiter limiter = redissonClient.getRateLimiter(uniqueName("reject"));
        limiter.trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("RRateLimiter 是滑動窗口:許可在各自取得後滿 interval 才釋放")
    void releasesEachPermitIntervalAfterItWasTaken() throws InterruptedException {
        RRateLimiter limiter = redissonClient.getRateLimiter(uniqueName("sliding"));
        limiter.trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(limiter.tryAcquire()).isTrue();
        Thread.sleep(1000);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        Thread.sleep(1200);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("RRateLimiter 設定 keepAlive 後,相關 key 都帶有 TTL")
    void limiterKeysHaveTtl() {
        String name = uniqueName("ttl");
        RRateLimiter limiter = redissonClient.getRateLimiter(name);
        limiter.trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(2), Duration.ofSeconds(30));
        limiter.tryAcquire();

        Set<String> keys = redisTemplate.keys("*" + name + "*");
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
            assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                    .as("TTL of %s", key)
                    .isBetween(1L, 30L);
        }
    }

    @Test
    @DisplayName("RSetCache 成員各自過期")
    void setCacheMemberExpires() throws InterruptedException {
        RSetCache<String> set = redissonClient.getSetCache(uniqueName("set"), StringCodec.INSTANCE);

        set.add("10.0.0.1", 1, TimeUnit.SECONDS);
        set.add("10.0.0.2", 30, TimeUnit.SECONDS);
        assertThat(set.contains("10.0.0.1")).isTrue();

        Thread.sleep(1500);

        assertThat(set.contains("10.0.0.1")).isFalse();
        assertThat(set.contains("10.0.0.2")).isTrue();
    }
}
