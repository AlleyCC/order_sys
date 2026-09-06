package com.example.orderSystem.service;

import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.exception.InsufficientBalanceException;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * createUserOrder 的併發回歸測試。
 *
 * 不變式:同一使用者的「凍結總額」永遠不得超過其餘額。
 * 可用餘額的檢查與品項寫入必須是原子的 —— 中間不能讓另一個請求插進來讀到同一份餘額。
 *
 * 併發是時序相關的,所以每個情境都跑多輪;而且不變式必須「每一輪都成立」,
 * 不是「大部分成立」。
 */
class OrderConcurrentFreezeTest extends AbstractIntegrationTest {

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

    @Test
    @DisplayName("餘額只夠一筆:併發下兩筆同一張單 → 只能成功一筆,凍結不超過餘額")
    void onlyOneSucceedsWhenBalanceCoversOne() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = seedUser(100);                 // 100 夠一筆 70,不夠兩筆 140
            String orderId = seedOrder(userId);

            Result r = runConcurrently(userId, orderId, orderId);

            assertThat(r.successCount())
                    .as("第 %d 輪:餘額 100 只夠一筆 70,不該有兩筆同時成功", round)
                    .isEqualTo(1);
            assertThat(r.errors())
                    .as("第 %d 輪:被擋下的那筆應該是餘額不足", round)
                    .singleElement()
                    .isInstanceOf(InsufficientBalanceException.class);
            assertInvariant(userId, round);
        }
    }

    @Test
    @DisplayName("餘額夠兩筆:併發下兩筆 → 兩筆都要成功(不可誤殺)")
    void bothSucceedWhenBalanceCoversBoth() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = seedUser(200);                 // 200 夠兩筆 70
            String orderId = seedOrder(userId);

            Result r = runConcurrently(userId, orderId, orderId);

            assertThat(r.successCount())
                    .as("第 %d 輪:兩筆都買得起,加鎖不該把合法的下單擋掉", round)
                    .isEqualTo(2);
            assertInvariant(userId, round);
        }
    }

    @Test
    @DisplayName("跨訂單:同一人同時在兩張不同的團下單 → 凍結一樣不得超過餘額")
    void invariantHoldsAcrossDifferentOrders() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String userId = seedUser(100);
            String orderA = seedOrder(userId);
            String orderB = seedOrder(userId);             // 不同團,但凍結是以「人」為單位算的

            Result r = runConcurrently(userId, orderA, orderB);

            assertThat(r.successCount())
                    .as("第 %d 輪:兩張不同的團,凍結一樣共用同一份餘額", round)
                    .isEqualTo(1);
            assertInvariant(userId, round);
        }
    }

    /** 核心不變式:凍結總額 <= 餘額,亦即可用餘額不得為負。 */
    private void assertInvariant(String userId, int round) {
        long balance = userMapper.selectById(userId).getBalance();
        long frozen = orderItemMapper.getFrozenAmount(userId);
        assertThat(frozen)
                .as("第 %d 輪:凍結 %d 超過餘額 %d", round, frozen, balance)
                .isLessThanOrEqualTo(balance);

        long available = (long) orderService.getUserAccount(userId).get("availableBalance");
        assertThat(available).as("第 %d 輪:可用餘額不該是負數", round).isNotNegative();
    }

    // ========== helpers ==========

    private String seedUser(long balance) {
        User user = new User();
        user.setUserId("race-" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserName("Race Tester");
        user.setPassword("$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW");
        user.setRole("employee");
        user.setBalance(balance);
        userMapper.insert(user);
        return user.getUserId();
    }

    private String seedOrder(String userId) {
        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setStoreId("store001");
        order.setCreatedBy(userId);
        order.setOrderName("併發測試團");
        order.setStatus(OrderStatus.OPEN);
        order.setDeadline(LocalDateTime.now().plusHours(1));
        orderMapper.insert(order);
        return order.getOrderId();
    }

    private record Result(int successCount, List<Throwable> errors) {}

    /** 兩條執行緒對齊後同時下單,回傳成功筆數與被擋下的例外。 */
    private Result runConcurrently(String userId, String orderIdA, String orderIdB) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> attempt(userId, orderIdA, ready, go, success, errors)),
                    pool.submit(() -> attempt(userId, orderIdB, ready, go, success, errors)));

            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(success.get(), errors);
    }

    private Void attempt(String userId, String orderId, CountDownLatch ready, CountDownLatch go,
                         AtomicInteger success, List<Throwable> errors) {
        CreateOrderItemRequest req = new CreateOrderItemRequest();
        req.setOrderId(orderId);
        req.setMenuId(MENU_ID);
        req.setQuantity(1);
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            orderService.createUserOrder(req, userId);
            success.incrementAndGet();
        } catch (Throwable t) {
            errors.add(t);
        }
        return null;
    }
}
