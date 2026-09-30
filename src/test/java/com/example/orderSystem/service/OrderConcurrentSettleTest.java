package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.exception.ConflictException;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 結算的重試與併發回歸測試。
 *
 * 不變式:同一張訂單只能被扣款一次。
 * 自動重試(CLOSED 繼續扣款)與團主手動 pay_order 可能同時跑,
 * 扣款前必須先用條件式 UPDATE 搶下訂單狀態,只有搶到的那個能扣。
 */
class OrderConcurrentSettleTest extends AbstractIntegrationTest {

    private static final int MENU_ID = 1;        // 招牌鍋貼(10入), unit_price = 70
    private static final int UNIT_PRICE = 70;
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
    private TransactionMapper transactionMapper;

    @Test
    @DisplayName("卡在 CLOSED 的訂單:再跑一次 settleOrder 會完成扣款")
    void closedOrderIsSettledOnRetry() {
        String userId = seedUser(1000);
        String orderId = seedClosedOrderWithItem(userId);

        orderService.settleOrder(orderId);

        assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.SETTLED);
        assertThat(userMapper.selectById(userId).getBalance()).isEqualTo(1000 - UNIT_PRICE);
    }

    @Test
    @DisplayName("自動重試與手動 pay_order 同時結算同一張單 → 只扣一次款")
    void concurrentSettlementDebitsOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = seedUser(1000);
            String orderId = seedClosedOrderWithItem(userId);

            List<Throwable> errors = runConcurrently(
                    () -> orderService.settleOrder(orderId),
                    () -> orderService.payOrder(orderId));

            assertThat(userMapper.selectById(userId).getBalance())
                    .as("第 %d 輪:只能扣一次 70", round)
                    .isEqualTo(1000 - UNIT_PRICE);
            assertThat(transactionMapper.selectCount(
                    new LambdaQueryWrapper<Transaction>().eq(Transaction::getOrderId, orderId)))
                    .as("第 %d 輪:交易紀錄只能有一筆", round)
                    .isEqualTo(1);
            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.SETTLED);
            assertThat(errors)
                    .as("第 %d 輪:沒搶到的只能是手動 pay_order 的 ConflictException", round)
                    .allMatch(e -> e instanceof ConflictException);
        }
    }

    @Test
    @DisplayName("團主連按兩次 pay_order → 只扣一次款,另一次回 409")
    void doublePayOrderDebitsOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = seedUser(1000);
            String orderId = seedClosedOrderWithItem(userId);

            List<Throwable> errors = runConcurrently(
                    () -> orderService.payOrder(orderId),
                    () -> orderService.payOrder(orderId));

            assertThat(userMapper.selectById(userId).getBalance())
                    .as("第 %d 輪:只能扣一次 70", round)
                    .isEqualTo(1000 - UNIT_PRICE);
            assertThat(errors)
                    .as("第 %d 輪:沒搶到的那次應該是 ConflictException", round)
                    .singleElement()
                    .isInstanceOf(ConflictException.class);
        }
    }

    // ========== helpers ==========

    private String seedUser(long balance) {
        User user = new User();
        user.setUserId("settle-" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserName("Settle Tester");
        user.setPassword("$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW");
        user.setBalance(balance);
        userMapper.insert(user);
        return user.getUserId();
    }

    private String seedClosedOrderWithItem(String userId) {
        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setStoreId("store001");
        order.setCreatedBy(userId);
        order.setOrderName("結算測試團");
        order.setStatus(OrderStatus.CLOSED);
        order.setDeadline(LocalDateTime.now().minusMinutes(1));
        orderMapper.insert(order);

        OrderItem item = new OrderItem();
        item.setOrderId(order.getOrderId());
        item.setUserId(userId);
        item.setMenuId(MENU_ID);
        item.setProductName("招牌鍋貼(10入)");
        item.setUnitPrice(UNIT_PRICE);
        item.setQuantity(1);
        orderItemMapper.insert(item);
        return order.getOrderId();
    }

    /** 兩條執行緒對齊後同時執行,回傳丟出的例外。 */
    private List<Throwable> runConcurrently(Runnable a, Runnable b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : List.of(a, b)) {
                futures.add(pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        task.run();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return errors;
    }
}
