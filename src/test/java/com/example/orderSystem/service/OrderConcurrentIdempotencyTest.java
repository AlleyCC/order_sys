package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.response.CreateOrderItemResult;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.OrderItemIdempotencyKey;
import com.example.orderSystem.mapper.OrderItemIdempotencyKeyMapper;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.example.orderSystem.support.TestFixtures.seedOpenOrder;
import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一個 Idempotency-Key 的併發下單。
 *
 * 不變式:同一使用者、同一 key,無論幾個請求同時抵達,最多只寫入一筆品項;
 * 後到者等先到者結束 —— 先到者成功就回放它的結果,失敗就自己重新執行。
 */
class OrderConcurrentIdempotencyTest extends AbstractIntegrationTest {

    private static final int MENU_ID = 1;        // 招牌鍋貼(10入), unit_price = 70
    private static final int ROUNDS = 5;

    @Autowired
    private OrderService orderService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OrderItemIdempotencyKeyMapper idempotencyKeyMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("同 key 兩個請求同時抵達 → 只寫入一筆,兩者 itemId 相同,恰一個是回放")
    void sameKeyConcurrentRequestsWriteOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = seedUser(userMapper, "idem", 1000);
            String orderId = seedOpenOrder(orderMapper, userId);

            List<CreateOrderItemResult> results = runConcurrently(userId, orderId, "k-" + round);

            assertThat(results).as("第 %d 輪:兩個請求都該成功", round).hasSize(2);
            assertThat(results.get(0).itemId())
                    .as("第 %d 輪:兩個請求該拿到同一個 itemId", round)
                    .isEqualTo(results.get(1).itemId());
            assertThat(results).filteredOn(CreateOrderItemResult::replayed)
                    .as("第 %d 輪:恰有一個是回放", round)
                    .hasSize(1);
            assertThat(itemsOf(orderId))
                    .as("第 %d 輪:只該寫入一筆品項", round)
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("先到者持有 key 後回滾 → 等待中的請求接著重新執行,寫入一筆且不是回放")
    void waiterReExecutesWhenHolderRollsBack() throws Exception {
        String userId = seedUser(userMapper, "idem", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            Future<CreateOrderItemResult> waiter = transactionTemplate.execute(status -> {
                // 扮演「先到者」:照 createUserOrder 的順序鎖 users、插入同一個 key,但不 commit
                holdKey(userId, "k1", null);
                Future<CreateOrderItemResult> f = pool.submit(() -> placeOrder(userId, orderId, "k1"));
                awaitLockWait(f);
                status.setRollbackOnly();                      // 先到者失敗
                return f;
            });

            CreateOrderItemResult result = waiter.get(30, TimeUnit.SECONDS);
            assertThat(result.replayed()).isFalse();
            assertThat(itemsOf(orderId)).singleElement()
                    .extracting(OrderItem::getItemId).isEqualTo(result.itemId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("先到者持有 key 後 commit → 等待中的請求回放先到者的 itemId,不寫入")
    void waiterReplaysWhenHolderCommits() throws Exception {
        String userId = seedUser(userMapper, "idem", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            Future<CreateOrderItemResult> waiter = transactionTemplate.execute(status -> {
                holdKey(userId, "k1", 424242);                 // 先到者寫入的品項
                Future<CreateOrderItemResult> f = pool.submit(() -> placeOrder(userId, orderId, "k1"));
                awaitLockWait(f);
                return f;                                      // 正常結束 → commit
            });

            CreateOrderItemResult result = waiter.get(30, TimeUnit.SECONDS);
            assertThat(result.replayed()).isTrue();
            assertThat(result.itemId()).isEqualTo(424242);
            assertThat(itemsOf(orderId)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    // ========== helpers ==========

    private void holdKey(String userId, String key, Integer itemId) {
        userMapper.selectForUpdate(userId);
        OrderItemIdempotencyKey record = new OrderItemIdempotencyKey();
        record.setUserId(userId);
        record.setIdempotencyKey(key);
        record.setItemId(itemId);
        idempotencyKeyMapper.insert(record);
    }

    /**
     * 等到被測的請求卡在 users 的行鎖上(也就是確實在等先到者),最多 10 秒。
     * 用 processlist 而非 information_schema.innodb_trx:後者是 InnoDB 的快取表,
     * 在本測試的交易內查詢時看不到其他連線的交易。
     */
    private void awaitLockWait(Future<?> waiter) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.processlist "
                            + "WHERE id <> CONNECTION_ID() AND info LIKE 'SELECT * FROM users WHERE user_id = % FOR UPDATE'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            if (waiter.isDone()) {
                throw new AssertionError("被測的請求沒有等待先到者就結束了");
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("被測的請求沒有進入鎖等待");
    }

    private CreateOrderItemResult placeOrder(String userId, String orderId, String key) {
        CreateOrderItemRequest req = new CreateOrderItemRequest();
        req.setOrderId(orderId);
        req.setMenuId(MENU_ID);
        req.setQuantity(1);
        return orderService.createUserOrder(req, userId, key);
    }

    private List<OrderItem> itemsOf(String orderId) {
        return orderItemMapper.selectList(new QueryWrapper<OrderItem>().eq("order_id", orderId));
    }

    /** 兩條執行緒對齊後以同一個 key 同時下單;任一個拋例外就讓測試失敗 */
    private List<CreateOrderItemResult> runConcurrently(String userId, String orderId, String key) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Callable<CreateOrderItemResult> attempt = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return placeOrder(userId, orderId, key);
            };
            List<Future<CreateOrderItemResult>> futures = List.of(pool.submit(attempt), pool.submit(attempt));

            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<CreateOrderItemResult> results = new ArrayList<>();
            for (Future<CreateOrderItemResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
