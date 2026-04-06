package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.request.CreateOrderItemRequest;
import com.example.orderSystem.dto.request.CreateOrderRequest;
import com.example.orderSystem.dto.request.DeleteOrderItemRequest;
import com.example.orderSystem.entity.*;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.exception.*;
import com.example.orderSystem.mapper.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
            when(userMapper.selectById("alice")).thenReturn(user);
            lenient().when(orderItemMapper.selectList(any())).thenReturn(List.of());
            lenient().when(orderMapper.selectList(any())).thenReturn(List.of());
            when(orderItemMapper.insert((OrderItem) any())).thenReturn(1);

            assertThatCode(() -> orderService.createUserOrder(createItemReq("ord-001", 1, 2), "alice"))
                    .doesNotThrowAnyException();
            verify(orderItemMapper).insert((OrderItem) any());
        }

        @Test
        @DisplayName("訂單不存在 → ResourceNotFoundException")
        void orderNotFound() {
            when(orderMapper.selectById("bad")).thenReturn(null);

            assertThatThrownBy(() -> orderService.createUserOrder(createItemReq("bad", 1, 1), "alice"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("該筆訂單不存在");
        }

        @Test
        @DisplayName("訂單非 OPEN → BadRequestException")
        void orderNotOpen() {
            Order order = createOrder("ord-001", "bob", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.createUserOrder(createItemReq("ord-001", 1, 1), "alice"))
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
            when(userMapper.selectById("alice")).thenReturn(user);
            lenient().when(orderMapper.selectList(any())).thenReturn(List.of());
            lenient().when(orderItemMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> orderService.createUserOrder(createItemReq("ord-001", 1, 1), "alice"))
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
                    deleteReq("ord-001", "1"), "alice", "employee"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("刪除別人的品項 → ForbiddenException")
        void deleteOtherItem() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            OrderItem item = createOrderItem(1, "ord-001", "charlie", 70);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectById(1)).thenReturn(item);

            assertThatThrownBy(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "alice", "employee"))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("admin 可刪除任何品項")
        void adminCanDelete() {
            Order order = createOrder("ord-001", "bob", OrderStatus.OPEN);
            OrderItem item = createOrderItem(1, "ord-001", "charlie", 70);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectById(1)).thenReturn(item);
            when(orderItemMapper.deleteById(1)).thenReturn(1);

            assertThatCode(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "admin", "admin"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("itemId=all → 訂單狀態改 CANCELLED")
        void deleteAll() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateById((Order) any())).thenReturn(1);

            orderService.deleteUserOrder(deleteReq("ord-001", "all"), "alice", "employee");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("訂單非 OPEN → IllegalStateException")
        void orderNotOpen() {
            Order order = createOrder("ord-001", "bob", OrderStatus.SETTLED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.deleteUserOrder(
                    deleteReq("ord-001", "1"), "alice", "employee"))
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

        @Test
        @DisplayName("團主取消 OPEN 訂單 → 成功")
        void ownerCancelsOpen() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateById((Order) any())).thenReturn(1);

            orderService.cancelOrder("ord-001", "alice", "employee");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("團主取消 CLOSED 訂單 → 成功")
        void ownerCancelsClosed() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderMapper.updateById((Order) any())).thenReturn(1);

            orderService.cancelOrder("ord-001", "alice", "employee");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("非團主且非 admin → ForbiddenException")
        void notOwner() {
            Order order = createOrder("ord-001", "alice", OrderStatus.OPEN);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.cancelOrder("ord-001", "bob", "employee"))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("SETTLED 訂單 → IllegalStateException")
        void alreadySettled() {
            Order order = createOrder("ord-001", "alice", OrderStatus.SETTLED);
            when(orderMapper.selectById("ord-001")).thenReturn(order);

            assertThatThrownBy(() -> orderService.cancelOrder("ord-001", "alice", "employee"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ========== pay_order ==========

    @Nested
    @DisplayName("payOrder")
    class PayOrder {

        @Test
        @DisplayName("CLOSED 訂單全員餘額足夠 → SETTLED")
        void settleSuccess() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            OrderItem item1 = createOrderItem(1, "ord-001", "alice", 105);
            OrderItem item2 = createOrderItem(2, "ord-001", "bob", 140);
            User alice = createUser("alice", 5000L);
            User bob = createUser("bob", 5000L);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(item1, item2));
            when(userMapper.casDebit("alice", 105)).thenReturn(1);
            when(userMapper.casDebit("bob", 140)).thenReturn(1);
            when(userMapper.selectById("alice")).thenReturn(alice);
            when(userMapper.selectById("bob")).thenReturn(bob);
            when(transactionMapper.insert((Transaction) any())).thenReturn(1);
            when(orderMapper.updateById((Order) any())).thenReturn(1);

            orderService.payOrder("ord-001");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.SETTLED);
            verify(transactionMapper, times(2)).insert((Transaction) any());
        }

        @Test
        @DisplayName("餘額不足 → FAILED + rollback")
        void insufficientBalance() {
            Order order = createOrder("ord-001", "alice", OrderStatus.CLOSED);
            OrderItem item = createOrderItem(1, "ord-001", "alice", 9999);

            when(orderMapper.selectById("ord-001")).thenReturn(order);
            when(orderItemMapper.selectList(any())).thenReturn(List.of(item));
            when(userMapper.casDebit("alice", 9999)).thenReturn(0); // balance not enough

            assertThatThrownBy(() -> orderService.payOrder("ord-001"))
                    .isInstanceOf(InsufficientBalanceException.class);
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
}
