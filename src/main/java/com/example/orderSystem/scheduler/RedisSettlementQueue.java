package com.example.orderSystem.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSettlementQueue {

    // key 都帶 {order:settlement} hash tag:Lua 腳本一次操作多個 key,在 Redis Cluster 必須落在同一個 slot
    private static final String KEY = "{order:settlement}:queue";
    private static final String RETRY_KEY = "{order:settlement}:retries";
    /** 已被認領、正在結算的訂單;score 是租約到期時間,逾期代表認領者可能已經消失 */
    private static final String PROCESSING_KEY = "{order:settlement}:processing";
    /** 重試次數用完、還沒通知到團主與管理員的訂單;通知成功才移除 */
    private static final String ABANDONED_KEY = "{order:settlement}:abandoned";

    /** 認領後多久沒回報完成,就當作認領者已消失;必須大於一批的處理時間 */
    static final long LEASE_MS = 60_000;
    private static final int CLAIM_BATCH_SIZE = 10;
    /** 單輪最多回收幾筆;回收只動 Redis,可以比認領多 */
    private static final int RECLAIM_BATCH_SIZE = 100;

    /** 第 n 次重試前要等的時間;用完就放棄,交給人工處理 */
    private static final long[] RETRY_DELAYS_MS = {1_000, 2_000, 4_000, 8_000, 16_000};

    private static final RedisScript<List<String>> CLAIM_SCRIPT = listScript("scripts/settlement-claim.lua");
    private static final RedisScript<List<String>> RECLAIM_SCRIPT = listScript("scripts/settlement-reclaim.lua");
    private static final RedisScript<Long> ACK_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/settlement-ack.lua"), Long.class);
    private static final RedisScript<Long> RETRY_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/settlement-retry.lua"), Long.class);

    private final StringRedisTemplate redisTemplate;

    /** 一次認領的結果;leaseUntil 是確認完成或重試時證明「租約還是我的」的憑據 */
    public record ClaimedBatch(List<String> orderIds, long leaseUntil) {
    }

    public void add(String orderId, LocalDateTime deadline) {
        double score = deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        redisTemplate.opsForZSet().add(KEY, orderId, score);
        log.info("Scheduled settlement for order {} at {}", orderId, deadline);
    }

    /**
     * 取消訂單時呼叫:從 queue 與 processing 一併移除,避免已取消的訂單在租約到期後被回收。
     * @return 是否從 queue 移除
     */
    public boolean remove(String orderId) {
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            conn.zRem(PROCESSING_KEY, orderId);
            conn.zRem(KEY, orderId);
            return null;
        });
        Long removed = (Long) results.get(1);
        boolean success = removed != null && removed > 0;
        if (success) {
            log.info("Removed settlement task for order {}", orderId);
        }
        return success;
    }

    /**
     * 認領到期訂單:在同一支 Lua 腳本裡查出到期者並搬進 processing。
     * 多個實例同時呼叫時,腳本一個接一個執行,各自拿到的訂單不會重疊。
     * @return 本次認領到的 orderId(依截止時間由早到晚)與租約到期時間
     */
    public ClaimedBatch claimDue(long nowEpochMs) {
        long leaseUntil = nowEpochMs + LEASE_MS;
        List<String> claimed = redisTemplate.execute(CLAIM_SCRIPT, List.of(KEY, PROCESSING_KEY),
                String.valueOf(nowEpochMs),
                String.valueOf(CLAIM_BATCH_SIZE),
                String.valueOf(leaseUntil));
        return new ClaimedBatch(claimed != null ? claimed : List.of(), leaseUntil);
    }

    /**
     * 結算正常結束:離開 processing,重試次數歸零。
     * 租約已被別的實例重新認領時不做任何事,由對方負責確認。
     * @return 是否確認成功(false 代表租約已不屬於自己)
     */
    public boolean ack(String orderId, long leaseUntil) {
        Long result = redisTemplate.execute(ACK_SCRIPT, List.of(PROCESSING_KEY, RETRY_KEY),
                orderId, String.valueOf(leaseUntil));
        boolean acked = result != null && result == 1;
        if (!acked) {
            log.info("Skip ack for order {}: lease was taken over by another instance", orderId);
        }
        return acked;
    }

    /**
     * 結算拋出例外:依 RETRY_DELAYS_MS 拉長間隔放回 queue 並離開 processing;
     * 次數用完則放進待通知集合。租約已被別的實例重新認領時不做任何事。
     */
    public void reEnqueue(String orderId, long leaseUntil) {
        List<String> args = new ArrayList<>();
        args.add(orderId);
        args.add(String.valueOf(leaseUntil));
        args.add(String.valueOf(System.currentTimeMillis()));
        for (long delay : RETRY_DELAYS_MS) {
            args.add(String.valueOf(delay));
        }
        Long attempt = redisTemplate.execute(RETRY_SCRIPT,
                List.of(KEY, PROCESSING_KEY, RETRY_KEY, ABANDONED_KEY), args.toArray());

        if (attempt == null || attempt < 0) {
            log.info("Skip retry for order {}: lease was taken over by another instance", orderId);
        } else if (attempt == 0) {
            log.warn("Giving up settlement for order {} after {} retries", orderId, RETRY_DELAYS_MS.length);
        } else {
            log.info("Re-enqueued settlement for order {} (retry {} in {} ms)",
                    orderId, attempt, RETRY_DELAYS_MS[attempt.intValue() - 1]);
        }
    }

    /**
     * 把租約已到期的訂單放回 queue(立即到期),每次回收計入重試次數;
     * 次數用完的放進待通知集合。
     */
    public void reclaimExpired(long nowEpochMs) {
        List<String> abandoned = redisTemplate.execute(RECLAIM_SCRIPT,
                List.of(KEY, PROCESSING_KEY, RETRY_KEY, ABANDONED_KEY),
                String.valueOf(nowEpochMs),
                String.valueOf(RETRY_DELAYS_MS.length),
                String.valueOf(RECLAIM_BATCH_SIZE));
        if (abandoned != null) {
            for (String orderId : abandoned) {
                log.warn("Giving up settlement for order {}: lease expired after {} retries",
                        orderId, RETRY_DELAYS_MS.length);
            }
        }
    }

    /** 重試次數用完、還沒通知的訂單 */
    public List<String> abandonedOrders() {
        Set<String> members = redisTemplate.opsForSet().members(ABANDONED_KEY);
        return members != null ? new ArrayList<>(members) : List.of();
    }

    /**
     * 取走一筆待通知的訂單;多個實例同時取時只有一個拿得到,避免重複通知。
     * @return 是否由自己取得
     */
    public boolean claimAbandoned(String orderId) {
        Long removed = redisTemplate.opsForSet().remove(ABANDONED_KEY, orderId);
        return removed != null && removed > 0;
    }

    /** 通知失敗時放回待通知集合,下一輪再試 */
    public void markAbandoned(String orderId) {
        redisTemplate.opsForSet().add(ABANDONED_KEY, orderId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisScript<List<String>> listScript(String path) {
        return (RedisScript) RedisScript.of(new ClassPathResource(path), List.class);
    }
}
