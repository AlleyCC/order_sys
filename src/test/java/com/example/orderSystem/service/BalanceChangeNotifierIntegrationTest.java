package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.enums.OrderStatus;
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

import java.time.LocalDateTime;

import static com.example.orderSystem.support.TestFixtures.insertItem;
import static com.example.orderSystem.support.TestFixtures.seedOpenOrder;
import static com.example.orderSystem.support.TestFixtures.seedOrder;
import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BalanceChangeNotifierIntegrationTest extends AbstractIntegrationTest {

    private static final int MENU_ID = 1;
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
    @DisplayName("下單:createUserOrder 自己的交易 commit 後 → 推送餘額變動信號")
    void pushesAfterOwnCommit() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);

        orderService.createUserOrder(createReq(orderId), userId, null);

        verify(notificationService).sendBalanceChanged(eq(userId), anyString());
    }

    @Test
    @DisplayName("下單:交易還沒 commit → 不推播;commit 之後才推")
    void doesNotPushBeforeCommit() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderService.createUserOrder(createReq(orderId), userId, null);
            verifyNoInteractions(notificationService);
        });

        verify(notificationService).sendBalanceChanged(eq(userId), anyString());
    }

    @Test
    @DisplayName("下單:交易 rollback → 品項沒有寫入,也不推播")
    void noPushWhenRolledBack() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            orderService.createUserOrder(createReq(orderId), userId, null);
            status.setRollbackOnly();
        });

        assertThat(orderItemMapper.getFrozenAmount(userId)).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("刪除品項:沒有交易 → 刪除後立即推送餘額變動信號")
    void deleteItemPushesBalance() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);
        orderService.createUserOrder(createReq(orderId), userId, null);
        clearInvocations(notificationService);

        orderService.deleteUserOrder(deleteReq(orderId, itemIdOf(orderId, userId)), userId);

        verify(notificationService).sendBalanceChanged(eq(userId), anyString());
    }

    @Test
    @DisplayName("結算:扣款 commit 後 → 每位參與者各收到一次信號,團主未參與則不收")
    void settlementPushesEachParticipant() {
        String owner = seedUser(userMapper, "owner", 0);
        String ming = seedUser(userMapper, "ming", 1000);
        String hua = seedUser(userMapper, "hua", 1000);
        String orderId = seedOrder(orderMapper, owner, OrderStatus.CLOSED, LocalDateTime.now().minusMinutes(1));
        insertItem(orderItemMapper, orderId, ming, UNIT_PRICE);
        insertItem(orderItemMapper, orderId, ming, UNIT_PRICE);
        insertItem(orderItemMapper, orderId, hua, UNIT_PRICE);

        orderService.payOrder(orderId);

        verify(notificationService).sendBalanceChanged(ming, "結算：" + orderMapper.selectById(orderId).getOrderName());
        verify(notificationService).sendBalanceChanged(eq(hua), anyString());
        verify(notificationService, never()).sendBalanceChanged(eq(owner), anyString());
    }

    @Test
    @DisplayName("刪除品項:推播失敗 → 刪除照樣成功,不回錯誤給使用者")
    void deleteItemSucceedsWhenPushFails() {
        String userId = seedUser(userMapper, "notify", 1000);
        String orderId = seedOpenOrder(orderMapper, userId);
        orderService.createUserOrder(createReq(orderId), userId, null);
        doThrow(new RedisConnectionFailureException("Redis 斷線"))
                .when(notificationService).sendBalanceChanged(anyString(), anyString());

        assertThatCode(() -> orderService.deleteUserOrder(
                deleteReq(orderId, itemIdOf(orderId, userId)), userId))
                .doesNotThrowAnyException();
        assertThat(orderItemMapper.getFrozenAmount(userId)).isZero();
    }

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
