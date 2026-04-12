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
    @DisplayName("reEnqueue 後能被再次 poll 到")
    void reEnqueue() {
        settlementQueue.reEnqueue("ord-retry");

        Set<String> due = settlementQueue.pollDueOrders(System.currentTimeMillis());
        assertThat(due).contains("ord-retry");
    }
}
