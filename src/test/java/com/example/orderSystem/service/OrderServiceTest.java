package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.CreateOrderRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.entity.*;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.event.BalanceChangedEvent;
import com.example.orderSystem.exception.*;
import com.example.orderSystem.mapper.*;
import com.example.orderSystem.scheduler.RedisSettlementQueue;
import com.example.orderSystem.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @InjectMocks
    private OrderService orderService;

    @Mock
    private StoreMapper storeMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private MenuMapper menuMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private TransactionMapper transactionMapper;
    @Mock
    private RedisSettlementQueue settlementQueue;
    @Mock
    private PaymentService paymentService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private RbacCacheService rbacCacheService;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private SettlementNotificationRecorder notificationRecorder;

    // ========== helpers ==========

    private Store createStore(String storeId) {
        Store s = new Store();
        s.setStoreId(storeId);
        s.setStoreName("Test Store");
        s.setMinOrderAmount(300);
        return s;
    }

    private Order createOrder(String orderId, String createdBy, OrderStatus status) {
        Order o = new Order();
        o.setOrderId(orderId);
        o.setStoreId("store001");
        o.setCreatedBy(createdBy);
        o.setOrderName("Test Order");
        o.setStatus(status);
        o.setDeadline(LocalDateTime.now().plusHours(2));
        return o;
    }

    private Notification notification(String userId) {
        Notification n = new Notification();
        n.setNotificationId("ntf-" + userId);
        n.setUserId(userId);
        n.setOrderId("ord-001");
        return n;
    }

    private Menu createMenu(int menuId, String storeId) {
        Menu m = new Menu();
        m.setMenuId(menuId);
        m.setStoreId(storeId);
        m.setProductName("Test Product");
        m.setUnitPrice(70);
        m.setIsAvailable(true);
        return m;
    }

    private User createUser(String userId, long balance) {
        User u = new User();
        u.setUserId(userId);
        u.setBalance(balance);
        return u;
    }

    private OrderItem createOrderItem(int itemId, String orderId, String userId, int subtotal) {
        OrderItem oi = new OrderItem();
        oi.setItemId(itemId);
        oi.setOrderId(orderId);
        oi.setUserId(userId);
        oi.setSubtotal(subtotal);
        return oi;
    }

    // ========== create_order ==========

    @Nested
    @DisplayName("createOrder")
    class CreateOrder {

        @Test
        @DisplayName("成功 → 回傳 orderId + storeId")
        void success() {
            when(storeMapper.selectById("store001")).thenReturn(createStore("store001"));
            when(orderMapper.insert((Order) any())).thenReturn(1);

            Map<String, String> result = orderService.createOrder(
                    createOrderReq("store001", "午餐團", "2026-12-31 12:00:00"), "alice");

            assertThat(result.get("orderId")).isNotBlank();
            assertThat(result.get("storeId")).isEqualTo("store001");
            verify(orderMapper).insert((Order) any());
        }

        @Test
        @DisplayName("店家不存在 → ResourceNotFoundException")
        void storeNotFound() {
            when(storeMapper.selectById("bad")).thenReturn(null);

            assertThatThrownBy(() -> orderService.createOrder(
                    createOrderReq("bad", "午餐團", "2026-12-31 12:00:00"), "alice"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("店家不存在");
        }

        private CreateOrderRequest createOrderReq(String storeId, String name, String deadline) {
            CreateOrderRequest req = new CreateOrderRequest();
            req.setStoreId(storeId);
            req.setOrderName(name);
            req.setDeadline(deadline);
            return req;
        }
    }

    // ========== create_user_order ==========

    @Nested
    @DisplayName("createUserOrder")
    class CreateUserOrder {

        @Test
        @DisplayName("成功 → 回傳 message")
        void success() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            Menu menu = createMenu(1, "store001");
            User user = createUser("alice", 5000L);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(menuMapper.selectById(1)).thenReturn(menu);
            when(userMapper.selectForUpdate("alice")).thenReturn(user);
            when(orderItemMapper.getFrozenAmount("alice")).thenReturn(0L);
            when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);

            assertThatCode(() -> orderService.createUserOrder(createItemReq("ord-001", 1, 2), "alice", null))
                    .doesNotThrowAnyException();
            verify(orderItemMapper).insert((OrderItem) any());
        }

        @Test
        @DisplayName("訂單不存在 → ResourceNotFoundException")
        void orderNotFound() {
            when(userMapper.selectForUpdate("alice")).thenReturn(createUser("alice", 5000L));
            when(orderMapper.selectById("bad")).thenReturn(null);

            assertThatThrownBy(() -> orderService.createUserOrder(createItemReq("bad", 1, 1), "alice", null))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("該筆訂單不存在");
        }

        @Test
        @DisplayName("訂單非 OPEN → BadRequestException")
        void orderNotOpen() {
            Order order = createOrder("ord-001", "bob", OrderStatus.CLOSED);
            when(userMapper.selectForUpdate("alice")).thenReturn(createUser("alice", 5000L));
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.createUserOrder(createItemReq("ord-001", 1, 1), "alice", null))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("餘額不足 → InsufficientBalanceException")
        void insufficientBalance() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            Menu menu = createMenu(1, "store001");
            menu.setUnitPrice(9999);
            User user = createUser("alice", 100L);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(menuMapper.selectById(1)).thenReturn(menu);
            when(userMapper.selectForUpdate("alice")).thenReturn(user);
            when(orderItemMapper.getFrozenAmount("alice")).thenReturn(0L);

            assertThatThrownBy(() -> orderService.createUserOrder(createItemReq("ord-001", 1, 1), "alice", null))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        private CreateOrderItemRequest createItemReq(String orderId, int menuId, int quantity) {
            CreateOrderItemRequest req = new CreateOrderItemRequest();
            req.setOrderId(orderId);
            req.setMenuId(menuId);
            req.setQuantity(quantity);
            return req;
        }
    }

    // ========== delete_user_order ==========

    @Nested
    @DisplayName("deleteUserOrder")
    class DeleteUserOrder {

        @Test
        @DisplayName("刪除自己的品項 → 成功")
        void deleteOwnItem() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            OrderItem item = createOrderItem(1, "ord-001", "alice", 70);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectById(1)).thenReturn(item);
            when(orderItemMapper.deleteById(1)).thenReturn(1);

            assertThatCode(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "alice"))
                    .doesNotThrowAnyException();
            verify(eventPublisher).publishEvent(
                    new BalanceChangedEvent("alice", "刪除品項：" + item.getProductName()));
        }

        @Test
        @DisplayName("刪除別人的品項 → ForbiddenException")
        void deleteOtherItem() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            OrderItem item = createOrderItem(1, "ord-001", "charlie", 70);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectById(1)).thenReturn(item);

            assertThatThrownBy(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "alice"))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("具客服角色者可刪除任何品項(跨 ownership 救援)")
        void rescueRoleCanDelete() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            OrderItem item = createOrderItem(1, "ord-001", "charlie", 70);

            when(rbacCacheService.getUserRoles("rescuer")).thenReturn(List.of("CUSTOMER_SERVICE"));
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectById(1)).thenReturn(item);
            when(orderItemMapper.deleteById(1)).thenReturn(1);

            assertThatCode(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "rescuer"))
                    .doesNotThrowAnyException();
            // 餘額變動通知的是品項主人,不是執行刪除的救援者
            verify(eventPublisher).publishEvent(
                    new BalanceChangedEvent("charlie", "刪除品項：" + item.getProductName()));
        }

        @Test
        @DisplayName("itemId=all → 只在 DB 仍是 OPEN 時改成 CANCELLED")
        void deleteAll() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CANCELLED, List.of(OrderStatus.OPEN)))
                    .thenReturn(1);

            orderService.deleteUserOrder(deleteReq("ord-001", "all"), "alice");

            verify(orderMapper, never()).updateById((Order) any());
            verify(settlementQueue).remove("ord-001");
        }

        @Test
        @DisplayName("itemId=all 但狀態已被改掉(例如剛開始結算)→ ConflictException,不移除排程")
        void deleteAllLosesRace() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CANCELLED, List.of(OrderStatus.OPEN)))
                    .thenReturn(0);

            assertThatThrownBy(() -> orderService.deleteUserOrder(deleteReq("ord-001", "all"), "alice"))
                    .isInstanceOf(ConflictException.class);
            verify(settlementQueue, never()).remove(any());
        }

        @Test
        @DisplayName("訂單非 OPEN → IllegalStateException")
        void orderNotOpen() {
            Order order = createOrder("ord-001", "bob", OrderStatus.SETTLED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "alice"))
                    .isInstanceOf(IllegalStateException.class);
        }

        private DeleteOrderItemRequest deleteReq(String orderId, String itemId) {
            DeleteOrderItemRequest req = new DeleteOrderItemRequest();
            req.setOrderId(orderId);
            req.setItemId(itemId);
            return req;
        }
    }

    // ========== cancel_order ==========

    @Nested
    @DisplayName("cancelOrder")
    class CancelOrder {

        private static final List<OrderStatus> CANCELLABLE = List.of(OrderStatus.OPEN, OrderStatus.CLOSED);

        @Test
        @DisplayName("團主取消 OPEN 訂單 → 成功")
        void ownerCancelsOpen() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CANCELLED, CANCELLABLE)).thenReturn(1);

            orderService.cancelOrder("ord-001", "alice");

            verify(orderMapper, never()).updateById((Order) any());
            verify(settlementQueue).remove("ord-001");
        }

        @Test
        @DisplayName("團主取消 CLOSED 訂單 → 成功")
        void ownerCancelsClosed() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CANCELLED, CANCELLABLE)).thenReturn(1);

            orderService.cancelOrder("ord-001", "alice");

            verify(orderMapper, never()).updateById((Order) any());
        }

        @Test
        @DisplayName("讀到 CLOSED 但寫入前已被結算 → ConflictException,不會蓋掉 SETTLED")
        void cancelLosesRaceToSettlement() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CANCELLED, CANCELLABLE)).thenReturn(0);

            assertThatThrownBy(() -> orderService.cancelOrder("ord-001", "alice"))
                    .isInstanceOf(ConflictException.class);
            verify(settlementQueue, never()).remove(any());
        }

        @Test
        @DisplayName("非團主且非 admin → ForbiddenException")
        void notOwner() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.cancelOrder("ord-001", "bob"))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("SETTLED 訂單 → IllegalStateException")
        void alreadySettled() {
            Order order = createOrder("ord-001", "alice", OrderStatus.SETTLED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.cancelOrder("ord-001", "alice"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ========== pay_order ==========

    @Nested
    @DisplayName("payOrder")
    class PayOrder {

        @Test
        @DisplayName("CLOSED 訂單 → paymentService.executePayment 被呼叫")
        void settleSuccess() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            orderService.payOrder("ord-001");

            verify(paymentService).executePayment(order);
        }

        @Test
        @DisplayName("扣款成功 → commit 後依寫入的通知逐則推播")
        void pushesWrittenNotificationsOnSuccess() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            Notification bob = notification("bob");
            Notification alice = notification("alice");
            when(paymentService.executePayment(order)).thenReturn(List.of(bob, alice));

            orderService.payOrder("ord-001");

            verify(notificationService).pushSettlement(bob);
            verify(notificationService).pushSettlement(alice);
        }

        @Test
        @DisplayName("餘額不足 → markFailed(帶所有不足者)、推播失敗通知、再拋出;不再無條件 updateById")
        void insufficientBalance() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            InsufficientBalanceException shortage = new InsufficientBalanceException(List.of("bob", "carol"));
            doThrow(shortage).when(paymentService).executePayment(order);
            Notification bob = notification("bob");
            when(paymentService.markFailed(order, List.of("bob", "carol"))).thenReturn(List.of(bob));

            assertThatThrownBy(() -> orderService.payOrder("ord-001")).isSameAs(shortage);
            verify(notificationService).pushSettlement(bob);
            verify(orderMapper, never()).updateById((Order) any());
        }

        @Test
        @DisplayName("推播失敗(成功時)→ payOrder 仍正常返回,其餘通知照推")
        void pushFailureDoesNotAffectSuccess() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            Notification bob = notification("bob");
            Notification alice = notification("alice");
            when(paymentService.executePayment(order)).thenReturn(List.of(bob, alice));
            doThrow(new RuntimeException("redis down")).when(notificationService).pushSettlement(bob);

            assertThatCode(() -> orderService.payOrder("ord-001")).doesNotThrowAnyException();
            verify(notificationService).pushSettlement(alice);
        }

        @Test
        @DisplayName("推播失敗(餘額不足時)→ 仍拋出原本的 InsufficientBalanceException")
        void pushFailureKeepsOriginalException() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            InsufficientBalanceException shortage = new InsufficientBalanceException(List.of("bob"));
            doThrow(shortage).when(paymentService).executePayment(order);
            Notification bob = notification("bob");
            when(paymentService.markFailed(order, List.of("bob"))).thenReturn(List.of(bob));
            doThrow(new RuntimeException("redis down")).when(notificationService).pushSettlement(bob);

            assertThatThrownBy(() -> orderService.payOrder("ord-001")).isSameAs(shortage);
        }

        @Test
        @DisplayName("扣款成功 → 每位參與者各推一次可用餘額(同一人多個品項只推一次)")
        void pushesBalanceToEachParticipantOnSuccess() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(
                    createOrderItem(1, "ord-001", "bob", 70),
                    createOrderItem(2, "ord-001", "bob", 30),
                    createOrderItem(3, "ord-001", "carol", 50)));
            when(userMapper.selectById("bob")).thenReturn(createUser("bob", 400));
            when(userMapper.selectById("carol")).thenReturn(createUser("carol", 950));
            when(orderItemMapper.getFrozenAmount("bob")).thenReturn(100L);
            when(orderItemMapper.getFrozenAmount("carol")).thenReturn(0L);

            orderService.payOrder("ord-001");

            verify(notificationService).sendBalanceUpdate("bob", 300, "結算：Test Order");
            verify(notificationService).sendBalanceUpdate("carol", 950, "結算：Test Order");
            verify(notificationService, times(2)).sendBalanceUpdate(any(), anyLong(), any());
        }

        @Test
        @DisplayName("餘額推播失敗 → payOrder 仍正常返回,其他人照推")
        void balancePushFailureDoesNotAffectSuccess() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(
                    createOrderItem(1, "ord-001", "bob", 70),
                    createOrderItem(2, "ord-001", "carol", 50)));
            when(userMapper.selectById("bob")).thenThrow(new RuntimeException("db hiccup"));
            when(userMapper.selectById("carol")).thenReturn(createUser("carol", 950));

            assertThatCode(() -> orderService.payOrder("ord-001")).doesNotThrowAnyException();
            verify(notificationService).sendBalanceUpdate(eq("carol"), anyLong(), any());
        }

        @Test
        @DisplayName("餘額不足 → 沒有人被扣款,不推餘額")
        void noBalancePushOnInsufficient() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            doThrow(new InsufficientBalanceException(List.of("bob"))).when(paymentService).executePayment(order);

            assertThatThrownBy(() -> orderService.payOrder("ord-001"))
                    .isInstanceOf(InsufficientBalanceException.class);
            verify(notificationService, never()).sendBalanceUpdate(any(), anyLong(), any());
        }

        @Test
        @DisplayName("SETTLED 訂單 → ConflictException")
        void alreadySettled() {
            Order order = createOrder("ord-001", "alice", OrderStatus.SETTLED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.payOrder("ord-001"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("該訂單已結算，無法進行付款");
        }

        @Test
        @DisplayName("OPEN 訂單 → IllegalStateException")
        void stillOpen() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.payOrder("ord-001"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ========== settle (自動結算) ==========

    @Nested
    @DisplayName("settleOrder")
    class SettleOrder {

        @Test
        @DisplayName("CLOSED 訂單(上次扣款中斷)→ 繼續扣款,不再改一次狀態")
        void closedOrderResumesPayment() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            orderService.settleOrder("ord-001");

            verify(paymentService).executePayment(order);
            verify(orderMapper, never()).updateById((Order) any());
            verify(orderMapper, never()).updateStatusIfIn(any(), any(), any());
        }

        @Test
        @DisplayName("OPEN 訂單 → 只在 DB 仍是 OPEN 時改成 CLOSED,再扣款")
        void openOrderClosedConditionally() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CLOSED, List.of(OrderStatus.OPEN)))
                    .thenReturn(1);

            orderService.settleOrder("ord-001");

            verify(orderMapper, never()).updateById((Order) any());
            verify(paymentService).executePayment(order);
        }

        @Test
        @DisplayName("讀到 OPEN 但寫入前已被取消 → 跳過,不會把 CANCELLED 蓋回 CLOSED、也不扣款")
        void openOrderCancelledConcurrentlyIsSkipped() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateStatusIfIn("ord-001", OrderStatus.CLOSED, List.of(OrderStatus.OPEN)))
                    .thenReturn(0);

            orderService.settleOrder("ord-001");

            verify(paymentService, never()).executePayment(any());
        }

        @Test
        @DisplayName("FAILED 訂單 → 跳過,自動結算不重試餘額不足的單")
        void failedOrderSkipped() {
            Order order = createOrder("ord-001", "alice", OrderStatus.FAILED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            orderService.settleOrder("ord-001");

            verify(paymentService, never()).executePayment(any());
        }

        @Test
        @DisplayName("扣款時發現已被別人結算(ConflictException)→ 視為完成,不往外丟")
        void concurrentlySettledIsNotAnError() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            doThrow(new ConflictException("訂單狀態已變更，無法結算"))
                    .when(paymentService).executePayment(order);

            assertThatCode(() -> orderService.settleOrder("ord-001")).doesNotThrowAnyException();
            verify(paymentService, never()).markFailed(any(), any());
            verify(notificationService, never()).pushSettlement(any());
        }

        @Test
        @DisplayName("扣款已 commit、推播失敗 → 不往外丟(否則被當成結算失敗重排),也不中斷其他人的通知")
        void successNotificationFailureIsNotASettlementFailure() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(paymentService.executePayment(order))
                    .thenReturn(List.of(notification("alice"), notification("bob")));
            // 第一則推播失敗、第二則成功;兩個人都要被嘗試通知
            doThrow(new RedisConnectionFailureException("Redis 斷線"))
                    .doNothing()
                    .when(notificationService).pushSettlement(any());

            assertThatCode(() -> orderService.settleOrder("ord-001")).doesNotThrowAnyException();
            verify(notificationService, times(2)).pushSettlement(any());
        }

        @Test
        @DisplayName("餘額不足已標記 FAILED、推播失敗 → 同樣不往外丟,也不中斷其他人的通知")
        void failedNotificationFailureIsNotASettlementFailure() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            doThrow(new InsufficientBalanceException(List.of("bob")))
                    .when(paymentService).executePayment(order);
            when(paymentService.markFailed(order, List.of("bob")))
                    .thenReturn(List.of(notification("alice"), notification("bob")));
            doThrow(new RedisConnectionFailureException("Redis 斷線"))
                    .doNothing()
                    .when(notificationService).pushSettlement(any());

            assertThatCode(() -> orderService.settleOrder("ord-001")).doesNotThrowAnyException();
            verify(notificationService, times(2)).pushSettlement(any());
        }

        @Test
        @DisplayName("扣款已 commit、查詢要推餘額給誰時 DB 出錯 → 一樣不往外丟")
        void participantLookupFailureIsNotASettlementFailure() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectList(any()))
                    .thenThrow(new DataAccessResourceFailureException("DB 斷線"));

            assertThatCode(() -> orderService.settleOrder("ord-001")).doesNotThrowAnyException();
            verify(paymentService).executePayment(order);
        }
    }

    @Nested
    @DisplayName("notifySettlementAbandoned")
    class NotifySettlementAbandoned {

        @Test
        @DisplayName("通知團主與管理員(客服、超級管理員),同一人只通知一次")
        void notifiesOrganizerAndAdminsOnce() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(roleMapper.selectUserIdsByRoleNames(argThat(roles ->
                    roles.containsAll(List.of("SUPER_ADMIN", "CUSTOMER_SERVICE")))))
                    .thenReturn(List.of("admin", "alice"));

            Notification toAlice = notification("alice");
            Notification toAdmin = notification("admin");
            when(notificationRecorder.recordAbandoned(order, "alice")).thenReturn(toAlice);
            when(notificationRecorder.recordAbandoned(order, "admin")).thenReturn(toAdmin);

            orderService.notifySettlementAbandoned("ord-001");

            verify(notificationRecorder).recordAbandoned(order, "alice");
            verify(notificationRecorder).recordAbandoned(order, "admin");
            verifyNoMoreInteractions(notificationRecorder);
            verify(notificationService).pushSettlement(toAlice);
            verify(notificationService).pushSettlement(toAdmin);
        }

        @Test
        @DisplayName("某收件人已有這張單的通知(重送)→ 不推播該人,其他人照常寫入與推播")
        void duplicateIsSkippedWithoutPush() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(roleMapper.selectUserIdsByRoleNames(any())).thenReturn(List.of("admin"));
            when(notificationRecorder.recordAbandoned(order, "alice"))
                    .thenThrow(new DuplicateKeyException("uk_user_dedup"));
            Notification toAdmin = notification("admin");
            when(notificationRecorder.recordAbandoned(order, "admin")).thenReturn(toAdmin);

            assertThatCode(() -> orderService.notifySettlementAbandoned("ord-001")).doesNotThrowAnyException();

            verify(notificationService).pushSettlement(toAdmin);
            verifyNoMoreInteractions(notificationService);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = {"SETTLED", "FAILED", "CANCELLED"})
        @DisplayName("訂單已到終態(例如結算成功後、ack 前機器當掉又被回收放棄)→ 不發「系統結算失敗」通知")
        void skipsWhenOrderAlreadyFinished(OrderStatus status) {
            Order order = createOrder("ord-001", "alice", status);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            orderService.notifySettlementAbandoned("ord-001");

            verifyNoInteractions(notificationRecorder);
            verifyNoInteractions(notificationService);
        }
    }
}
