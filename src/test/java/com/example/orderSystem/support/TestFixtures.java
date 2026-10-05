package com.example.orderSystem.support;

import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 測試共用的資料準備。
 *
 * seed* 產生隨機 ID:整合測試共用同一個 MySQL 容器、資料不會清掉,隨機後綴讓測試之間不會互相撞到。
 * insert* 用呼叫端指定的 ID:給每個測試結束就 rollback 的 mapper 測試,或需要特定前綴以便事後清理的測試。
 */
public final class TestFixtures {

    /** 種子資料共用的 bcrypt 雜湊,測試只需要欄位有值,不會拿來登入 */
    private static final String PASSWORD_HASH = "$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW";

    /** 菜單 1 是 store001 的招牌鍋貼(10入) */
    private static final int MENU_ID = 1;

    private TestFixtures() {
    }

    /** 建立一個指定餘額的使用者,userId 為 {@code prefix-<隨機 8 碼>} */
    public static String seedUser(UserMapper userMapper, String prefix, long balance) {
        return insertUser(userMapper, prefix + "-" + UUID.randomUUID().toString().substring(0, 8), balance);
    }

    public static String insertUser(UserMapper userMapper, String userId, long balance) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName("測試使用者");
        user.setPassword(PASSWORD_HASH);
        user.setBalance(balance);
        userMapper.insert(user);
        return userId;
    }

    /** 建立一張 store001 的開團中訂單,一小時後截止 */
    public static String seedOpenOrder(OrderMapper orderMapper, String createdBy) {
        return seedOrder(orderMapper, createdBy, OrderStatus.OPEN, LocalDateTime.now().plusHours(1));
    }

    public static String seedOrder(OrderMapper orderMapper, String createdBy,
                                   OrderStatus status, LocalDateTime deadline) {
        return insertOrder(orderMapper, UUID.randomUUID().toString(), createdBy, status, deadline);
    }

    /** 指定 ID 建立 store001 的訂單,一小時後截止 */
    public static String insertOrder(OrderMapper orderMapper, String orderId, String createdBy, OrderStatus status) {
        return insertOrder(orderMapper, orderId, createdBy, status, LocalDateTime.now().plusHours(1));
    }

    private static String insertOrder(OrderMapper orderMapper, String orderId, String createdBy,
                                      OrderStatus status, LocalDateTime deadline) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setStoreId("store001");
        order.setCreatedBy(createdBy);
        order.setOrderName("測試團");
        order.setStatus(status);
        order.setDeadline(deadline);
        orderMapper.insert(order);
        return orderId;
    }

    /** 在訂單裡加一個數量 1 的品項,回傳 itemId */
    public static Integer insertItem(OrderItemMapper orderItemMapper, String orderId, String userId, int unitPrice) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setUserId(userId);
        item.setMenuId(MENU_ID);
        item.setProductName("測試品項");
        item.setUnitPrice(unitPrice);
        item.setQuantity(1);
        orderItemMapper.insert(item);
        return item.getItemId();
    }
}
