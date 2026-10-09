package com.example.orderSystem.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.response.CreateOrderItemResult;
import com.example.orderSystem.entity.OrderItemIdempotencyKey;
import com.example.orderSystem.mapper.OrderItemIdempotencyKeyMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.service.OrderService;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.List;
import java.util.stream.IntStream;

import static com.example.orderSystem.support.TestFixtures.seedOpenOrder;
import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency-Key 記錄的清理。排程在測試環境關閉(app.scheduler.enabled=false),
 * 這裡直接呼叫清理方法;created_at 以 DB 的 NOW() 往前推,模擬已建立一段時間的記錄。
 */
class IdempotencyKeyCleanupJobTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyKeyCleanupJob cleanupJob;
    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderItemIdempotencyKeyMapper idempotencyKeyMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectProvider<ScheduledTaskHolder> scheduledTaskHolders;

    private String userId;

    @BeforeEach
    void setUp() {
        userId = seedUser(userMapper, "clean", 1000);
    }

    @Test
    @DisplayName("只刪除超過 24 小時的記錄:25 小時前的刪除,1 小時前的保留")
    void deletesOnlyExpired() {
        insertKeyCreatedHoursAgo("old", 25);
        insertKeyCreatedHoursAgo("recent", 1);

        cleanupJob.purgeExpired();

        assertThat(keysOf(userId)).extracting(OrderItemIdempotencyKey::getIdempotencyKey)
                .containsExactly("recent");
    }

    @Test
    @DisplayName("過期筆數超過一批 → 一次清理就全部刪除")
    void deletesMoreThanOneBatch() {
        int count = IdempotencyKeyCleanupJob.BATCH_SIZE + 100;
        List<Object[]> rows = IntStream.rangeClosed(1, count)
                .mapToObj(n -> new Object[]{userId, "bulk-" + n, n})
                .toList();
        jdbcTemplate.batchUpdate("""
                INSERT INTO order_item_idempotency_keys (user_id, idempotency_key, item_id, created_at)
                VALUES (?, ?, ?, NOW() - INTERVAL 25 HOUR)
                """, rows);
        assertThat(keysOf(userId)).hasSize(count);

        cleanupJob.purgeExpired();

        assertThat(keysOf(userId)).isEmpty();
    }

    @Test
    @DisplayName("記錄被清理後以同一 key 再下單 → 視為新的下單,寫入新品項")
    void sameKeyAfterPurgeIsNewOrder() {
        String orderId = seedOpenOrder(orderMapper, userId);
        CreateOrderItemResult first = orderService.createUserOrder(request(orderId), userId, "k1");
        jdbcTemplate.update(
                "UPDATE order_item_idempotency_keys SET created_at = NOW() - INTERVAL 25 HOUR WHERE user_id = ?",
                userId);

        cleanupJob.purgeExpired();
        CreateOrderItemResult second = orderService.createUserOrder(request(orderId), userId, "k1");

        assertThat(second.replayed()).isFalse();
        assertThat(second.itemId()).isNotEqualTo(first.itemId());
    }

    @Test
    @DisplayName("關閉自動結算(app.scheduler.enabled=false)時,清理仍有排程")
    void cleanupIsScheduledIndependentlyOfSettlement() {
        // 測試環境的 app.scheduler.enabled=false,RedisSettlementConsumer 不存在
        assertThat(scheduledTaskHolders.stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.getTask().toString()))
                .anyMatch(name -> name.endsWith("IdempotencyKeyCleanupJob.purgeExpired"));
    }

    // ========== helpers ==========

    private void insertKeyCreatedHoursAgo(String key, int hoursAgo) {
        jdbcTemplate.update("""
                INSERT INTO order_item_idempotency_keys (user_id, idempotency_key, item_id, created_at)
                VALUES (?, ?, 1, NOW() - INTERVAL ? HOUR)
                """, userId, key, hoursAgo);
    }

    private List<OrderItemIdempotencyKey> keysOf(String userId) {
        return idempotencyKeyMapper.selectList(
                new QueryWrapper<OrderItemIdempotencyKey>().eq("user_id", userId));
    }

    private CreateOrderItemRequest request(String orderId) {
        CreateOrderItemRequest req = new CreateOrderItemRequest();
        req.setOrderId(orderId);
        req.setMenuId(1);
        req.setQuantity(1);
        return req;
    }
}
