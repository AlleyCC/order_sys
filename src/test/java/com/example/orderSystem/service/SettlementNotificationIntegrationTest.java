package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.NotificationType;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.exception.InsufficientBalanceException;
import com.example.orderSystem.mapper.NotificationMapper;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 結算結果通知:通知要和它描述的狀態變化一起成立。
 * 每個測試自己建使用者與訂單,不依賴 seed data,也不互相干擾。
 */
class SettlementNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final int MENU_ID = 1;        // 招牌鍋貼(10入), unit_price = 70
    private static final int UNIT_PRICE = 70;

    @Autowired OrderService orderService;
    @Autowired UserMapper userMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired OrderItemMapper orderItemMapper;
    @Autowired TransactionMapper transactionMapper;
    @Autowired NotificationMapper notificationMapper;
    @Autowired PaymentService paymentService;
    @Autowired RoleMapper roleMapper;

    @Nested
    @DisplayName("全有全無:找出所有餘額不足者")
    class FindAllShortUsers {

        @Test
        @DisplayName("三人中兩人不足 → 例外列出這兩人,三人餘額與交易紀錄皆未變")
        void reportsEveryShortUser() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 50);
            String hua = seedUser("hua", 500);
            String mei = seedUser("mei", 30);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, ming, hua, mei);

            InsufficientBalanceException e = catchThrowableOfType(
                    InsufficientBalanceException.class, () -> orderService.payOrder(orderId));

            assertThat(e).isNotNull();
            assertThat(e.getShortUserIds()).containsExactlyInAnyOrder(ming, mei);
            assertThat(e.getMessage()).contains(ming).contains(mei).doesNotContain(hua);
            assertThat(balanceOf(ming)).isEqualTo(50);
            assertThat(balanceOf(hua)).isEqualTo(500);
            assertThat(balanceOf(mei)).isEqualTo(30);
            assertThat(transactionMapper.selectCount(
                    new LambdaQueryWrapper<Transaction>().eq(Transaction::getOrderId, orderId))).isZero();
            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("結算成功:通知與扣款一起成立")
    class Succeeded {

        @Test
        @DisplayName("團主未參與 → 每位參與者一則(金額+餘額),團主一則(總金額)")
        void ownerNotParticipating() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 500);
            String hua = seedUser("hua", 300);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, ming, hua);

            orderService.payOrder(orderId);

            assertThat(notificationsOf(ming, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_SUCCEEDED);
                assertThat(n.getContent()).contains("便當團").contains("70").contains("430");
            });
            assertThat(notificationsOf(hua, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_SUCCEEDED);
                assertThat(n.getContent()).contains("70").contains("230");
            });
            assertThat(notificationsOf(owner, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_SUCCEEDED_OWNER);
                assertThat(n.getContent()).contains("140");
            });
        }

        @Test
        @DisplayName("團主同時是參與者 → 團主只有團主版一則,含本人金額與餘額")
        void ownerAlsoParticipates() {
            String owner = seedUser("owner", 1000);
            String ming = seedUser("ming", 500);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, owner, ming);

            orderService.payOrder(orderId);

            assertThat(notificationsOf(owner, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_SUCCEEDED_OWNER);
                assertThat(n.getContent()).contains("140").contains("70").contains("930");
            });
            assertThat(notificationsOf(ming, orderId)).singleElement()
                    .extracting(Notification::getType).isEqualTo(NotificationType.SETTLEMENT_SUCCEEDED);
        }

        @Test
        @DisplayName("扣款未成立(有人不足、交易回滾)→ 沒有任何結算成功通知")
        void rolledBackLeavesNoSuccessNotification() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 500);
            String hua = seedUser("hua", 10);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, ming, hua);

            catchThrowableOfType(InsufficientBalanceException.class, () -> orderService.payOrder(orderId));

            assertThat(notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getOrderId, orderId)
                    .in(Notification::getType, NotificationType.SETTLEMENT_SUCCEEDED,
                            NotificationType.SETTLEMENT_SUCCEEDED_OWNER))).isZero();
        }
    }

    @Nested
    @DisplayName("結算失敗:通知與訂單轉為 FAILED 一起成立")
    class Failed {

        @Test
        @DisplayName("依身分收到不同內容:不足者要儲值、足夠者知道被卡住、團主看到名單")
        void contentByRole() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 10);
            String hua = seedUser("hua", 500);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, ming, hua);

            catchThrowableOfType(InsufficientBalanceException.class, () -> orderService.payOrder(orderId));

            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(notificationsOf(ming, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_INSUFFICIENT);
                assertThat(n.getContent()).contains("儲值");
            });
            assertThat(notificationsOf(hua, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_BLOCKED);
                assertThat(n.getContent()).contains("凍結").doesNotContain("你的餘額不足");
            });
            assertThat(notificationsOf(owner, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_FAILED_OWNER);
                assertThat(n.getContent()).contains("ming").doesNotContain("hua");
            });
        }

        @Test
        @DisplayName("團主本人不足 → 團主只有團主版一則,名單列出自己")
        void ownerIsShort() {
            String owner = seedUser("owner", 10);
            String ming = seedUser("ming", 500);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, owner, ming);

            catchThrowableOfType(InsufficientBalanceException.class, () -> orderService.payOrder(orderId));

            assertThat(notificationsOf(owner, orderId)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(NotificationType.SETTLEMENT_FAILED_OWNER);
                assertThat(n.getContent()).contains("owner");
            });
            assertThat(notificationsOf(ming, orderId)).singleElement()
                    .extracting(Notification::getType).isEqualTo(NotificationType.SETTLEMENT_BLOCKED);
        }

        @Test
        @DisplayName("訂單已被其他結算改為 SETTLED → 不改回 FAILED、不寫失敗通知")
        void alreadySettledIsNotMarkedFailed() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 10);
            String orderId = seedOrder(owner, OrderStatus.SETTLED, ming);

            List<Notification> written = paymentService.markFailed(orderMapper.selectById(orderId), List.of(ming));

            assertThat(written).isEmpty();
            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.SETTLED);
            assertThat(notificationsOf(ming, orderId)).isEmpty();
            assertThat(notificationsOf(owner, orderId)).isEmpty();
        }

        @Test
        @DisplayName("同一張單失敗兩次(團主重試)→ 不足者有兩則")
        void everyFailureIsANewNotification() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 10);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, ming);

            catchThrowableOfType(InsufficientBalanceException.class, () -> orderService.payOrder(orderId));
            catchThrowableOfType(InsufficientBalanceException.class, () -> orderService.payOrder(orderId));

            assertThat(notificationsOf(ming, orderId))
                    .hasSize(2)
                    .allMatch(n -> n.getType() == NotificationType.SETTLEMENT_INSUFFICIENT);
        }
    }

    @Nested
    @DisplayName("自動重試用完:通知不重複")
    class Abandoned {

        @Test
        @DisplayName("通知流程重送 → 團主與每位救援管理員各恰一則")
        void resendDoesNotDuplicate() {
            String owner = seedUser("owner", 0);
            String ming = seedUser("ming", 500);
            String orderId = seedOrder(owner, OrderStatus.CLOSED, ming);

            orderService.notifySettlementAbandoned(orderId);
            orderService.notifySettlementAbandoned(orderId);

            List<String> rescuers = roleMapper.selectUserIdsByRoleNames(Set.of("SUPER_ADMIN", "CUSTOMER_SERVICE"));
            assertThat(rescuers).as("seed data 至少要有一位救援管理員,這個測試才有意義").isNotEmpty();
            for (String recipient : concat(owner, rescuers)) {
                assertThat(notificationsOf(recipient, orderId))
                        .as("收件人 %s", recipient)
                        .singleElement()
                        .extracting(Notification::getType).isEqualTo(NotificationType.SETTLEMENT_ABANDONED);
            }
            assertThat(notificationsOf(ming, orderId)).as("一般參與者不收").isEmpty();
        }

        @Test
        @DisplayName("訂單已結算 → 不寫入任何重試用完通知")
        void finishedOrderIsSkipped() {
            String owner = seedUser("owner", 0);
            String orderId = seedOrder(owner, OrderStatus.SETTLED);

            orderService.notifySettlementAbandoned(orderId);

            assertThat(notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getOrderId, orderId))).isZero();
        }

        private List<String> concat(String first, List<String> rest) {
            List<String> all = new ArrayList<>(List.of(first));
            rest.stream().filter(id -> !id.equals(first)).forEach(all::add);
            return all;
        }
    }

    // ========== helpers ==========

    private List<Notification> notificationsOf(String userId, String orderId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getOrderId, orderId));
    }

    private long balanceOf(String userId) {
        return userMapper.selectById(userId).getBalance();
    }

    private String seedUser(String name, long balance) {
        User user = new User();
        user.setUserId(name + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserName(name);
        user.setPassword("$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW");
        user.setBalance(balance);
        userMapper.insert(user);
        return user.getUserId();
    }

    /** 每位參與者各訂一份 70 元 */
    private String seedOrder(String owner, OrderStatus status, String... participants) {
        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setStoreId("store001");
        order.setCreatedBy(owner);
        order.setOrderName("便當團");
        order.setStatus(status);
        order.setDeadline(LocalDateTime.now().minusMinutes(1));
        orderMapper.insert(order);

        for (String userId : participants) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getOrderId());
            item.setUserId(userId);
            item.setMenuId(MENU_ID);
            item.setProductName("招牌鍋貼(10入)");
            item.setUnitPrice(UNIT_PRICE);
            item.setQuantity(1);
            orderItemMapper.insert(item);
        }
        return order.getOrderId();
    }
}
