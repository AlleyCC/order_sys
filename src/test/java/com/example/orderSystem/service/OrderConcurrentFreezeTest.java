package com.example.orderSystem.service;

import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重現用測試(非回歸測試):驗證 createUserOrder 目前對「可用餘額」是 check-then-act,
 * 中間沒有鎖,同一使用者併發下單時兩邊都會讀到同一份可用餘額而放行。
 *
 * 預期(修好之前):兩筆都成功 → 凍結金額 > 實際餘額 → availableBalance 變負數,
 * 但不會超扣,問題被推遲到結算時由 casDebit 擋下,整張單變 FAILED。
 */
class OrderConcurrentFreezeTest extends AbstractIntegrationTest {

    private static final int MENU_ID = 1;        // 招牌鍋貼(10入), unit_price = 70
    private static final int UNIT_PRICE = 70;
    private static final long BALANCE = 100;     // 只夠買一份
    private static final int ROUNDS = 5;         // 併發是時序相關,多跑幾輪提高重現率

    @Autowired
    private OrderService orderService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;

    @Test
    @DisplayName("併發下單:同一人兩筆同時進來,可用餘額 check-then-act 造成超額凍結")
    void concurrentCreateUserOrderOverFreezes() throws Exception {
        String racedUser = null;
        String racedOrder = null;
        int maxSuccess = 0;

        for (int round = 0; round < ROUNDS && racedUser == null; round++) {
            String userId = "race" + round + "-" + UUID.randomUUID().toString().substring(0, 6);
            String orderId = seed(userId);

            int success = runConcurrently(userId, orderId);
            maxSuccess = Math.max(maxSuccess, success);
            if (success == 2) {
                racedUser = userId;
                racedOrder = orderId;
            }
        }

        assertThat(racedUser)
                .as("跑了 %d 輪都沒重現(每輪最多 %d 筆成功);"
                        + "若穩定只有 1 筆成功,代表這條路徑已經有防護", ROUNDS, maxSuccess)
                .isNotNull();

        // --- 現況 1:凍結金額超過實際餘額,可用餘額變負數 ---
        long frozen = orderItemMapper.getFrozenAmount(racedUser);
        assertThat(frozen).isEqualTo(2L * UNIT_PRICE);
        assertThat(frozen).isGreaterThan(BALANCE);

        long available = (long) orderService.getUserAccount(racedUser).get("availableBalance");
        assertThat(available).isEqualTo(BALANCE - frozen);
        assertThat(available).isNegative();

        // --- 現況 2:不會超扣,問題被推遲到結算 → casDebit 擋下 → 整張單 FAILED ---
        orderService.settleOrder(racedOrder);

        assertThat(orderMapper.selectById(racedOrder).getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(userMapper.selectById(racedUser).getBalance()).isEqualTo(BALANCE);
    }

    /** 建一個餘額剛好只夠買一份的使用者 + 一張未截止的 OPEN 訂單。 */
    private String seed(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName("Race " + userId);
        user.setPassword("$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW");
        user.setRole("employee");
        user.setBalance(BALANCE);
        userMapper.insert(user);

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

    /** 兩條執行緒同時對同一 user 下同一張單,回傳成功筆數。 */
    private int runConcurrently(String userId, String orderId) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> attempt(userId, orderId, ready, go, success)),
                    pool.submit(() -> attempt(userId, orderId, ready, go, success)));

            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        return success.get();
    }

    private Void attempt(String userId, String orderId,
                         CountDownLatch ready, CountDownLatch go, AtomicInteger success) {
        CreateOrderItemRequest req = new CreateOrderItemRequest();
        req.setOrderId(orderId);
        req.setMenuId(MENU_ID);
        req.setQuantity(1);
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            orderService.createUserOrder(req, userId);
            success.incrementAndGet();
        } catch (Exception ignored) {
            // 餘額不足 → 這正是「有防護」的情況,不計入成功
        }
        return null;
    }
}
