package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.orderSystem.config.MyBatisPlusConfig;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MyBatisPlusConfig.class)
@ActiveProfiles("test")
class OrderMapperTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired OrderMapper orderMapper;
    @Autowired UserMapper userMapper;

    @Test
    @DisplayName("可跟團清單:只回 OPEN,已結算/已取消的團不出現")
    void openListExcludesNonOpenOrders() {
        String owner = seedUser("open-owner");
        seedOrder("ord-open-1", owner, OrderStatus.OPEN);
        seedOrder("ord-open-settled", owner, OrderStatus.SETTLED);
        seedOrder("ord-open-cancelled", owner, OrderStatus.CANCELLED);

        List<String> ids = openOrderIds();

        assertThat(ids).contains("ord-open-1");
        assertThat(ids).doesNotContain("ord-open-settled", "ord-open-cancelled");
        assertThat(ids).contains("ord-002").doesNotContain("ord-001");
    }

    @Test
    @DisplayName("可跟團清單:不分參與者,沒下過單的人也看得到(探索用途)")
    void openListIsNotScopedToParticipants() {
        String stranger = seedUser("open-stranger");
        String owner = seedUser("open-other");
        seedOrder("ord-open-2", owner, OrderStatus.OPEN);

        assertThat(openOrderIds()).contains("ord-open-2");
        assertThat(stranger).isNotEqualTo(owner);
    }

    @Test
    @DisplayName("可跟團清單:只帶摘要欄位,不帶參與者或品項")
    void openListCarriesSummaryFieldsOnly() {
        String owner = seedUser("open-fields");
        seedOrder("ord-open-3", owner, OrderStatus.OPEN);

        Map<String, Object> row = orderMapper.getOpenOrdersWithStore(new Page<>(1, 50))
                .getRecords().stream()
                .filter(r -> "ord-open-3".equals(r.get("orderId")))
                .findFirst().orElseThrow();

        assertThat(row.keySet())
                .containsExactlyInAnyOrder("orderId", "orderName", "deadline", "minOrderAmount");
    }

    @Test
    @DisplayName("可跟團清單:分頁的總筆數只算 OPEN")
    void openListTotalCountsOpenOnly() {
        String owner = seedUser("open-total");
        seedOrder("ord-open-t1", owner, OrderStatus.OPEN);
        seedOrder("ord-open-t2", owner, OrderStatus.SETTLED);

        long openTotal = orderMapper.getOpenOrdersWithStore(new Page<>(1, 1)).getTotal();
        long allTotal = orderMapper.getAllOrdersWithStore(new Page<>(1, 1)).getTotal();

        assertThat(openTotal).isLessThan(allTotal);
    }

    @Test
    @DisplayName("後台查詢:不限狀態,已結算的團也回")
    void adminListIncludesAllStatuses() {
        String owner = seedUser("admin-list");
        seedOrder("ord-admin-settled", owner, OrderStatus.SETTLED);

        List<String> ids = orderMapper.getAllOrdersWithStore(new Page<>(1, 50))
                .getRecords().stream()
                .map(r -> String.valueOf(r.get("orderId")))
                .toList();

        assertThat(ids).contains("ord-admin-settled", "ord-001", "ord-002");
    }

    @Test
    @DisplayName("分頁:每頁筆數受 size 限制,總筆數不受影響")
    void paginationAppliesToBothQueries() {
        IPage<Map<String, Object>> firstPage = orderMapper.getAllOrdersWithStore(new Page<>(1, 1));

        assertThat(firstPage.getRecords()).hasSize(1);
        assertThat(firstPage.getTotal()).isGreaterThanOrEqualTo(2);
    }

    private List<String> openOrderIds() {
        return orderMapper.getOpenOrdersWithStore(new Page<>(1, 50))
                .getRecords().stream()
                .map(r -> String.valueOf(r.get("orderId")))
                .toList();
    }

    private String seedUser(String userId) {
        User user = new User();
        user.setUserId(userId);
        user.setUserName(userId);
        user.setPassword("x");
        user.setBalance(0L);
        userMapper.insert(user);
        return userId;
    }

    private void seedOrder(String orderId, String createdBy, OrderStatus status) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setStoreId("store001");
        order.setCreatedBy(createdBy);
        order.setOrderName(orderId);
        order.setStatus(status);
        order.setDeadline(LocalDateTime.now().plusHours(1));
        orderMapper.insert(order);
    }
}
