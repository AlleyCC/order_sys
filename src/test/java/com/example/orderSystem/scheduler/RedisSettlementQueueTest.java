package com.example.orderSystem.scheduler;

import com.example.orderSystem.scheduler.RedisSettlementQueue.ClaimedBatch;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RedisSettlementQueueTest {

    private static final String QUEUE = "{order:settlement}:queue";
    private static final String PROCESSING = "{order:settlement}:processing";
    private static final String RETRIES = "{order:settlement}:retries";
    private static final String ABANDONED = "{order:settlement}:abandoned";

    /** 固定的「現在」;時間由參數傳入,測試不需要真的等待 */
    private static final long NOW = 1_800_000_000_000L;
    /** 在 NOW 認領時寫入的租約到期時間 */
    private static final long LEASE = NOW + RedisSettlementQueue.LEASE_MS;

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
        var keys = redisTemplate.keys("{order:settlement}:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ========== add / remove ==========

    @Test
    @DisplayName("add 後,截止時間已過的訂單能被 claimDue 認領")
    void addThenClaimDue() {
        settlementQueue.add("ord-001", LocalDateTime.now().minusMinutes(1));

        assertThat(settlementQueue.claimDue(System.currentTimeMillis()).orderIds()).contains("ord-001");
    }

    @Test
    @DisplayName("add 後,截止時間未到的訂單不會被 claimDue 認領")
    void futureOrderNotClaimed() {
        settlementQueue.add("ord-002", LocalDateTime.now().plusHours(1));

        assertThat(settlementQueue.claimDue(System.currentTimeMillis()).orderIds()).doesNotContain("ord-002");
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
    @DisplayName("remove(取消訂單時呼叫):也移出 processing;回傳值只反映是否從 queue 移除")
    void removeAlsoLeavesProcessing() {
        zadd(PROCESSING, "ord-cancel", LEASE);

        assertThat(settlementQueue.remove("ord-cancel")).isFalse();

        assertThat(score(PROCESSING, "ord-cancel")).isNull();
    }

    // ========== claimDue:認領 = 把到期訂單從 queue 原子搬到 processing ==========

    @Test
    @DisplayName("claimDue 只取出到期的;取出者搬進 processing,score 是租約到期時間")
    void claimDueMovesDueOrdersToProcessing() {
        zadd(QUEUE, "ord-due", NOW - 1);
        zadd(QUEUE, "ord-future", NOW + 1);

        ClaimedBatch batch = settlementQueue.claimDue(NOW);

        assertThat(batch.orderIds()).containsExactly("ord-due");
        assertThat(batch.leaseUntil()).isEqualTo(LEASE);
        assertThat(score(QUEUE, "ord-due")).isNull();
        assertThat(score(PROCESSING, "ord-due")).isEqualTo((double) LEASE);
        assertThat(score(QUEUE, "ord-future")).isEqualTo((double) (NOW + 1));
        assertThat(score(PROCESSING, "ord-future")).isNull();
    }

    @Test
    @DisplayName("claimDue 一次最多 10 筆,依截止時間由早到晚;其餘留在 queue")
    void claimDueTakesEarliestTen() {
        for (int i = 0; i < 15; i++) {
            zadd(QUEUE, "ord-" + i, NOW - 100 + i);    // ord-0 最早到期
        }

        List<String> claimed = settlementQueue.claimDue(NOW).orderIds();

        assertThat(claimed).containsExactly(
                "ord-0", "ord-1", "ord-2", "ord-3", "ord-4",
                "ord-5", "ord-6", "ord-7", "ord-8", "ord-9");
        assertThat(redisTemplate.opsForZSet().size(QUEUE)).isEqualTo(5);
        assertThat(redisTemplate.opsForZSet().size(PROCESSING)).isEqualTo(10);
    }

    @Test
    @DisplayName("已在 processing 的訂單不會被 claimDue 再取出")
    void claimDueIgnoresProcessing() {
        zadd(PROCESSING, "ord-busy", NOW + 30_000);

        assertThat(settlementQueue.claimDue(NOW).orderIds()).isEmpty();
        assertThat(score(PROCESSING, "ord-busy")).isEqualTo((double) (NOW + 30_000));
    }

    @Test
    @DisplayName("兩個實例同時 claimDue 20 筆到期訂單:各拿到的沒有交集,合起來剛好 20 筆")
    void concurrentClaimsAreDisjoint() throws Exception {
        for (int i = 0; i < 20; i++) {
            zadd(QUEUE, "ord-" + i, NOW - 100 + i);
        }

        List<ClaimedBatch> results = runConcurrently(
                () -> settlementQueue.claimDue(NOW),
                () -> settlementQueue.claimDue(NOW));

        List<String> first = results.get(0).orderIds();
        List<String> second = results.get(1).orderIds();
        Set<String> union = new HashSet<>(first);
        union.addAll(second);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(union).hasSize(20);
    }

    // ========== ack:只有租約仍屬於自己時才生效 ==========

    @Test
    @DisplayName("ack:租約是自己的 → 離開 processing,重試次數被清除")
    void ackRemovesFromProcessingAndClearsRetries() {
        zadd(PROCESSING, "ord-done", LEASE);
        redisTemplate.opsForHash().put(RETRIES, "ord-done", "2");

        assertThat(settlementQueue.ack("ord-done", LEASE)).isTrue();

        assertThat(score(PROCESSING, "ord-done")).isNull();
        assertThat(redisTemplate.opsForHash().hasKey(RETRIES, "ord-done")).isFalse();
    }

    @Test
    @DisplayName("ack:租約已被別的實例重新認領 → 不動對方的 processing 紀錄與重試次數")
    void staleAckLeavesNewClaimerAlone() {
        long newLease = LEASE + 5_000;             // 原租約逾期後,別台重新認領寫入的新租約
        zadd(PROCESSING, "ord-taken", newLease);
        redisTemplate.opsForHash().put(RETRIES, "ord-taken", "1");

        assertThat(settlementQueue.ack("ord-taken", LEASE)).isFalse();

        assertThat(score(PROCESSING, "ord-taken")).isEqualTo((double) newLease);
        assertThat(redisTemplate.opsForHash().get(RETRIES, "ord-taken")).isEqualTo("1");
    }

    // ========== reEnqueue:結算例外後退避重試 ==========

    @Test
    @DisplayName("reEnqueue:訂單回到 queue(1 秒後到期),並離開 processing")
    void reEnqueueLeavesProcessing() {
        zadd(PROCESSING, "ord-retry", LEASE);

        long before = System.currentTimeMillis();
        settlementQueue.reEnqueue("ord-retry", LEASE);

        assertThat(score(QUEUE, "ord-retry").longValue())
                .isBetween(before + 1_000, System.currentTimeMillis() + 1_000);
        assertThat(score(PROCESSING, "ord-retry")).isNull();
    }

    @Test
    @DisplayName("reEnqueue 間隔依序 1、2、4、8、16 秒")
    void reEnqueueBacksOffExponentially() {
        long[] expectedDelays = {1_000, 2_000, 4_000, 8_000, 16_000};

        for (long delay : expectedDelays) {
            zadd(PROCESSING, "ord-backoff", LEASE);    // 每次重試前都先被認領
            long before = System.currentTimeMillis();
            settlementQueue.reEnqueue("ord-backoff", LEASE);

            Double score = score(QUEUE, "ord-backoff");
            assertThat(score.longValue()).isBetween(before + delay, System.currentTimeMillis() + delay);
        }
    }

    @Test
    @DisplayName("reEnqueue 次數用完:不在 queue 也不在 processing、次數清除,放進待通知集合")
    void reEnqueueGivesUpIntoAbandoned() {
        zadd(PROCESSING, "ord-giveup", LEASE);
        redisTemplate.opsForHash().put(RETRIES, "ord-giveup", "5");

        settlementQueue.reEnqueue("ord-giveup", LEASE);

        assertThat(score(QUEUE, "ord-giveup")).isNull();
        assertThat(score(PROCESSING, "ord-giveup")).isNull();
        assertThat(redisTemplate.opsForHash().hasKey(RETRIES, "ord-giveup")).isFalse();
        assertThat(settlementQueue.abandonedOrders()).containsExactly("ord-giveup");
    }

    @Test
    @DisplayName("reEnqueue:租約已被別的實例重新認領 → 不重試、不計次、不動對方的紀錄")
    void staleReEnqueueLeavesNewClaimerAlone() {
        long newLease = LEASE + 5_000;
        zadd(PROCESSING, "ord-taken", newLease);

        settlementQueue.reEnqueue("ord-taken", LEASE);

        assertThat(score(QUEUE, "ord-taken")).isNull();
        assertThat(score(PROCESSING, "ord-taken")).isEqualTo((double) newLease);
        assertThat(redisTemplate.opsForHash().hasKey(RETRIES, "ord-taken")).isFalse();
    }

    @Test
    @DisplayName("reEnqueue 後要等退避時間到才能被再次認領(第 1 次等 1 秒)")
    void reEnqueuedOrderIsClaimableAfterDelay() {
        zadd(PROCESSING, "ord-retry", LEASE);
        long before = System.currentTimeMillis();
        settlementQueue.reEnqueue("ord-retry", LEASE);

        assertThat(settlementQueue.claimDue(before).orderIds()).doesNotContain("ord-retry");
        assertThat(settlementQueue.claimDue(before + 1_000 + 500).orderIds()).contains("ord-retry");
    }

    @Test
    @DisplayName("ack 後重試次數歸零,下一次 reEnqueue 又從 1 秒開始")
    void ackResetsBackoff() {
        zadd(PROCESSING, "ord-clear", LEASE);
        settlementQueue.reEnqueue("ord-clear", LEASE);
        zadd(PROCESSING, "ord-clear", LEASE);
        settlementQueue.reEnqueue("ord-clear", LEASE);

        zadd(PROCESSING, "ord-clear", LEASE);
        settlementQueue.ack("ord-clear", LEASE);

        zadd(PROCESSING, "ord-clear", LEASE);
        long before = System.currentTimeMillis();
        settlementQueue.reEnqueue("ord-clear", LEASE);
        assertThat(score(QUEUE, "ord-clear").longValue())
                .isBetween(before + 1_000, System.currentTimeMillis() + 1_000);
    }

    // ========== reclaimExpired:租約逾期的放回 queue ==========

    @Test
    @DisplayName("租約未到期不回收")
    void reclaimSkipsUnexpiredLease() {
        zadd(PROCESSING, "ord-busy", NOW + 1);

        settlementQueue.reclaimExpired(NOW);

        assertThat(score(PROCESSING, "ord-busy")).isEqualTo((double) (NOW + 1));
        assertThat(score(QUEUE, "ord-busy")).isNull();
    }

    @Test
    @DisplayName("租約已到期:放回 queue 立即到期、離開 processing、重試次數 +1")
    void reclaimMovesExpiredBackToQueue() {
        zadd(PROCESSING, "ord-lost", NOW - 1);

        settlementQueue.reclaimExpired(NOW);

        assertThat(score(QUEUE, "ord-lost")).isEqualTo((double) NOW);
        assertThat(score(PROCESSING, "ord-lost")).isNull();
        assertThat(redisTemplate.opsForHash().get(RETRIES, "ord-lost")).isEqualTo("1");
        assertThat(settlementQueue.abandonedOrders()).isEmpty();
    }

    @Test
    @DisplayName("重試次數已用完又逾期:不放回 queue、離開 processing、次數清除,放進待通知集合")
    void reclaimGivesUpAfterMaxRetries() {
        zadd(PROCESSING, "ord-poison", NOW - 1);
        redisTemplate.opsForHash().put(RETRIES, "ord-poison", "5");

        settlementQueue.reclaimExpired(NOW);

        assertThat(score(QUEUE, "ord-poison")).isNull();
        assertThat(score(PROCESSING, "ord-poison")).isNull();
        assertThat(redisTemplate.opsForHash().hasKey(RETRIES, "ord-poison")).isFalse();
        assertThat(settlementQueue.abandonedOrders()).containsExactly("ord-poison");
    }

    @Test
    @DisplayName("兩個實例同時回收同一筆:只放回一次,重試次數只 +1")
    void concurrentReclaimCountsOnce() throws Exception {
        zadd(PROCESSING, "ord-lost", NOW - 1);

        runConcurrently(
                () -> { settlementQueue.reclaimExpired(NOW); return null; },
                () -> { settlementQueue.reclaimExpired(NOW); return null; });

        assertThat(redisTemplate.opsForZSet().size(QUEUE)).isEqualTo(1);
        assertThat(redisTemplate.opsForHash().get(RETRIES, "ord-lost")).isEqualTo("1");
    }

    // ========== 待通知集合:通知成功才算數,失敗放回 ==========

    @Test
    @DisplayName("claimAbandoned:同一筆只有一個實例拿得到,拿走後不在集合裡")
    void claimAbandonedOnlyOnce() {
        redisTemplate.opsForSet().add(ABANDONED, "ord-x");

        assertThat(settlementQueue.claimAbandoned("ord-x")).isTrue();
        assertThat(settlementQueue.claimAbandoned("ord-x")).isFalse();
        assertThat(settlementQueue.abandonedOrders()).isEmpty();
    }

    @Test
    @DisplayName("markAbandoned:通知失敗時放回,下一輪還拿得到")
    void markAbandonedPutsBack() {
        redisTemplate.opsForSet().add(ABANDONED, "ord-x");
        settlementQueue.claimAbandoned("ord-x");

        settlementQueue.markAbandoned("ord-x");

        assertThat(settlementQueue.abandonedOrders()).containsExactly("ord-x");
    }

    // ========== helpers ==========

    private void zadd(String key, String orderId, long score) {
        redisTemplate.opsForZSet().add(key, orderId, score);
    }

    private Double score(String key, String orderId) {
        return redisTemplate.opsForZSet().score(key, orderId);
    }

    /** 兩條執行緒對齊後同時執行,依提交順序回傳結果。 */
    private <T> List<T> runConcurrently(Callable<T> a, Callable<T> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
