package com.example.orderSystem.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.scheduler.RedisSettlementQueue.ClaimedBatch;
import com.example.orderSystem.service.OrderService;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static com.example.orderSystem.support.TestFixtures.insertItem;
import static com.example.orderSystem.support.TestFixtures.seedOrder;
import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 認領者在結算途中消失:租約到期後訂單被回收,由其他實例重新認領並完成結算。
 * 消費端在測試環境停用(app.scheduler.enabled=false),這裡直接呼叫佇列與 settleOrder 模擬每一步。
 */
class SettlementLeaseScenarioTest extends AbstractIntegrationTest {

    private static final int UNIT_PRICE = 70;    // 招牌鍋貼(10入)

    @Autowired
    private RedisSettlementQueue settlementQueue;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private OrderService orderService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private TransactionMapper transactionMapper;

    @BeforeEach
    void cleanRedis() {
        var keys = redisTemplate.keys("{order:settlement}:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("實例 A 認領後消失 → 租約到期被回收 → 實例 B 重新認領並完成結算,只扣一次款")
    void claimerDisappearsThenAnotherInstanceSettles() {
        String userId = seedUser(userMapper, "lease", 1000);
        String orderId = seedOpenOrderPastDeadline(userId);
        long t = System.currentTimeMillis();

        // 實例 A 認領,然後失聯:沒有 settleOrder,也沒有 ack
        ClaimedBatch claimedByA = settlementQueue.claimDue(t);
        assertThat(claimedByA.orderIds()).containsExactly(orderId);

        // 租約未到期前,別的實例認領不到
        assertThat(settlementQueue.claimDue(t + RedisSettlementQueue.LEASE_MS - 1).orderIds()).isEmpty();

        // 租約到期:回收後由實例 B 重新認領
        long later = t + RedisSettlementQueue.LEASE_MS + 1;
        settlementQueue.reclaimExpired(later);
        ClaimedBatch claimedByB = settlementQueue.claimDue(later);
        assertThat(claimedByB.orderIds()).containsExactly(orderId);

        // A 恢復後送出晚到的 ack:租約已經是 B 的,不能刪掉 B 的處理中紀錄
        assertThat(settlementQueue.ack(orderId, claimedByA.leaseUntil())).isFalse();
        assertThat(redisTemplate.opsForZSet().score("{order:settlement}:processing", orderId))
                .isEqualTo((double) claimedByB.leaseUntil());

        orderService.settleOrder(orderId);
        assertThat(settlementQueue.ack(orderId, claimedByB.leaseUntil())).isTrue();

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.SETTLED);
        assertThat(userMapper.selectById(userId).getBalance()).isEqualTo(1000 - UNIT_PRICE);
        assertThat(transactionMapper.selectCount(
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getOrderId, orderId)))
                .isEqualTo(1);
        assertThat(redisTemplate.opsForZSet().size("{order:settlement}:queue")).isZero();
        assertThat(redisTemplate.opsForZSet().size("{order:settlement}:processing")).isZero();
    }

    // ========== helpers ==========

    /** 截止時間已過、仍是 OPEN 的訂單,並排進結算佇列(與建立訂單時的排程相同) */
    private String seedOpenOrderPastDeadline(String userId) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(1);
        String orderId = seedOrder(orderMapper, userId, OrderStatus.OPEN, deadline);
        insertItem(orderItemMapper, orderId, userId, UNIT_PRICE);
        settlementQueue.add(orderId, deadline);
        return orderId;
    }
}
