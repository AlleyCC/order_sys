package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.example.orderSystem.support.TestFixtures.seedOpenOrder;
import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 餘額變動推播只能在資料已經寫進 DB 之後才送,而且推播失敗不能反過來讓操作失敗。
 *
 * createUserOrder 為了列鎖加上了 @Transactional,而 commit 是方法 return 之後才由 proxy 執行的,
 * 推播若直接寫在方法最後一行,仍然在交易之內:交易 rollback 時使用者已經收到「下單成功」。
 * 外面再包一層 TransactionTemplate,才看得到「createUserOrder 已經 return、但交易還沒 commit」這個時間點。
 *
 * deleteUserOrder 沒有交易,deleteById 當下就 commit;它走同一個事件,由 listener 的 fallbackExecution 立即處理。
 */
class BalanceChangeNotifierIntegrationTest extends AbstractIntegrationTest {

    private static final int MENU_ID = 1;        // 招牌鍋貼(10入), unit_price = 70
    private static final int UNIT_PRICE = 70;

    @MockitoBean
    private NotificationService notificationService;

    @Autowired
    private OrderService orderService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("下單:createUserOrder 自己的交易 commit 後 → 推送 commit 後的可用餘額")
    void pushesAfterOwnCommit() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);

        orderService.createUserOrder(createReq(orderId), userId);

        verify(notificationService).sendBalanceUpdate(eq(userId), eq(1000L - UNIT_PRICE), anyString());
    }

    @Test
    @DisplayName("下單:交易還沒 commit → 不推播;commit 之後才推")
    void doesNotPushBeforeCommit() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderService.createUserOrder(createReq(orderId), userId);
            verifyNoInteractions(notificationService);
        });

        verify(notificationService).sendBalanceUpdate(eq(userId), eq(1000L - UNIT_PRICE), anyString());
    }

    @Test
    @DisplayName("下單:交易 rollback → 品項沒有寫入,也不推播")
    void noPushWhenRolledBack() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderService.createUserOrder(createReq(orderId), userId);
            status.setRollbackOnly();
        });

        assertThat(orderItemMapper.getFrozenAmount(userId)).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("刪除品項:沒有交易 → 刪除後立即推送釋放凍結後的可用餘額")
    void deleteItemPushesBalance() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);
        orderService.createUserOrder(createReq(orderId), userId);
        clearInvocations(notificationService);

        orderService.deleteUserOrder(deleteReq(orderId, itemIdOf(orderId, userId)), userId);

        verify(notificationService).sendBalanceUpdate(eq(userId), eq(1000L), anyString());
    }

    @Test
    @DisplayName("刪除品項:推播失敗 → 刪除照樣成功,不回錯誤給使用者")
    void deleteItemSucceedsWhenPushFails() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);
        orderService.createUserOrder(createReq(orderId), userId);
        doThrow(new RedisConnectionFailureException("Redis 斷線"))
                .when(notificationService).sendBalanceUpdate(anyString(), anyLong(), anyString());

        assertThatCode(() -> orderService.deleteUserOrder(
                deleteReq(orderId, itemIdOf(orderId, userId)), userId))
                .doesNotThrowAnyException();
        assertThat(orderItemMapper.getFrozenAmount(userId)).isZero();
    }

    // ========== helpers ==========

    private CreateOrderItemRequest createReq(String orderId) {
        CreateOrderItemRequest req = new CreateOrderItemRequest();
        req.setOrderId(orderId);
        req.setMenuId(MENU_ID);
        req.setQuantity(1);
        return req;
    }

    private DeleteOrderItemRequest deleteReq(String orderId, String itemId) {
        DeleteOrderItemRequest req = new DeleteOrderItemRequest();
        req.setOrderId(orderId);
        req.setItemId(itemId);
        return req;
    }

    private String itemIdOf(String orderId, String userId) {
        OrderItem item = orderItemMapper.selectOne(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId)
                .eq(OrderItem::getUserId, userId));
        return String.valueOf(item.getItemId());
    }
}
