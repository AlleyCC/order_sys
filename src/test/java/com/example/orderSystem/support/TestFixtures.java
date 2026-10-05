package com.example.orderSystem.support;

import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 整合測試共用的資料準備。
 *
 * 整合測試共用同一個 MySQL 容器、資料不會清掉,所以 ID 一律加隨機後綴,
 * 測試之間不會互相撞到。
 */
public final class TestFixtures {

    /** 種子資料共用的 bcrypt 雜湊,測試只需要欄位有值,不會拿來登入 */
    private static final String PASSWORD_HASH = "$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW";

    private TestFixtures() {
    }

    /** 建立一個指定餘額的使用者,userId 為 {@code prefix-<隨機 8 碼>} */
    public static String seedUser(UserMapper userMapper, String prefix, long balance) {
        User user = new User();
        user.setUserId(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserName(prefix + " tester");
        user.setPassword(PASSWORD_HASH);
        user.setBalance(balance);
        userMapper.insert(user);
        return user.getUserId();
    }

    /** 建立一張 store001 的開團中訂單,一小時後截止 */
    public static String seedOpenOrder(OrderMapper orderMapper, String createdBy, String orderName) {
        Order order = new Order();
        order.setOrderId(UUID.randomUUID().toString());
        order.setStoreId("store001");
        order.setCreatedBy(createdBy);
        order.setOrderName(orderName);
        order.setStatus(OrderStatus.OPEN);
        order.setDeadline(LocalDateTime.now().plusHours(1));
        orderMapper.insert(order);
        return order.getOrderId();
    }
}
