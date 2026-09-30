package com.example.orderSystem.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RedisSettlementQueueTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RedisSettlementQueue settlementQueue;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanRedis() {
        var keys = redisTemplate.keys("order:settlement:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("add 後 pollDueOrders 能撈到已到期的訂單")
    void addAndPollDue() {
        settlementQueue.add("ord-001", LocalDateTime.now().minusMinutes(1));

        Set<String> due = settlementQueue.pollDueOrders(System.currentTimeMillis());
        assertThat(due).contains("ord-001");
    }

    @Test
    @DisplayName("未到期的訂單不會被 poll 出來")
    void futureOrderNotPolled() {
        settlementQueue.add("ord-002", LocalDateTime.now().plusHours(1));

        Set<String> due = settlementQueue.pollDueOrders(System.currentTimeMillis());
        assertThat(due).doesNotContain("ord-002");
    }

    @Test
    @DisplayName("remove 成功回傳 true，再次 remove 回傳 false")
    void removeReturnsCorrectly() {
        settlementQueue.add("ord-003", LocalDateTime.now().plusMinutes(10));

        assertThat(settlementQueue.remove("ord-003")).isTrue();
        assertThat(settlementQueue.remove("ord-003")).isFalse();
    }

    @Test
    @DisplayName("remove 不存在的 orderId 回傳 false")
    void removeNonexistent() {
        assertThat(settlementQueue.remove("ord-not-exist")).isFalse();
    }

    @Test
    @DisplayName("pollDueOrders 最多回傳 10 筆")
    void pollLimit() {
        for (int i = 0; i < 15; i++) {
            settlementQueue.add("ord-limit-" + i, LocalDateTime.now().minusMinutes(1));
        }

        Set<String> due = settlementQueue.pollDueOrders(System.currentTimeMillis());
        assertThat(due).hasSize(10);
    }

    @Test
    @DisplayName("reEnqueue 後要等退避時間到才能被再次 poll 到(第 1 次等 1 秒)")
    void reEnqueue() {
        long before = System.currentTimeMillis();
        assertThat(settlementQueue.reEnqueue("ord-retry")).isTrue();

        assertThat(settlementQueue.pollDueOrders(before)).doesNotContain("ord-retry");
        assertThat(settlementQueue.pollDueOrders(before + 1_000 + 500)).contains("ord-retry");
    }

    @Test
    @DisplayName("reEnqueue 間隔依序 1、2、4、8、16 秒")
    void reEnqueueBacksOffExponentially() {
        long[] expectedDelays = {1_000, 2_000, 4_000, 8_000, 16_000};

        for (long delay : expectedDelays) {
            long before = System.currentTimeMillis();
            assertThat(settlementQueue.reEnqueue("ord-backoff")).isTrue();

            Double score = redisTemplate.opsForZSet().score("order:settlement:queue", "ord-backoff");
            assertThat(score.longValue()).isBetween(before + delay, System.currentTimeMillis() + delay);
        }
    }

    @Test
    @DisplayName("重試滿 5 次後,第 6 次 reEnqueue 回傳 false 且不再入隊")
    void reEnqueueGivesUpAfterFiveRetries() {
        for (int i = 0; i < 5; i++) {
            assertThat(settlementQueue.reEnqueue("ord-giveup")).isTrue();
        }
        settlementQueue.remove("ord-giveup");

        assertThat(settlementQueue.reEnqueue("ord-giveup")).isFalse();
        assertThat(redisTemplate.opsForZSet().score("order:settlement:queue", "ord-giveup")).isNull();
    }

    @Test
    @DisplayName("clearRetries 後重試次數歸零,下一次 reEnqueue 又從 1 秒開始")
    void clearRetriesResetsBackoff() {
        settlementQueue.reEnqueue("ord-clear");
        settlementQueue.reEnqueue("ord-clear");

        settlementQueue.clearRetries("ord-clear");

        long before = System.currentTimeMillis();
        settlementQueue.reEnqueue("ord-clear");
        Double score = redisTemplate.opsForZSet().score("order:settlement:queue", "ord-clear");
        assertThat(score.longValue()).isBetween(before + 1_000, System.currentTimeMillis() + 1_000);
    }
}
