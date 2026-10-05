package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.test.autoconfigure.MybatisPlusTest;
import com.example.orderSystem.config.MyBatisPlusConfig;
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

import java.util.List;
import java.util.Map;

import static com.example.orderSystem.support.TestFixtures.insertOrder;
import static com.example.orderSystem.support.TestFixtures.insertUser;
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
        String owner = insertUser(userMapper, "open-owner", 0);
        insertOrder(orderMapper, "ord-open-1", owner, OrderStatus.OPEN);
        insertOrder(orderMapper, "ord-open-settled", owner, OrderStatus.SETTLED);
        insertOrder(orderMapper, "ord-open-cancelled", owner, OrderStatus.CANCELLED);

        List<String> ids = openOrderIds();

        assertThat(ids).contains("ord-open-1");
        assertThat(ids).doesNotContain("ord-open-settled", "ord-open-cancelled");
        assertThat(ids).contains("ord-002").doesNotContain("ord-001");
    }

    @Test
    @DisplayName("可跟團清單:不分參與者,沒下過單的人也看得到(探索用途)")
    void openListIsNotScopedToParticipants() {
        String stranger = insertUser(userMapper, "open-stranger", 0);
        String owner = insertUser(userMapper, "open-other", 0);
        insertOrder(orderMapper, "ord-open-2", owner, OrderStatus.OPEN);

        assertThat(openOrderIds()).contains("ord-open-2");
        assertThat(stranger).isNotEqualTo(owner);
    }

    @Test
    @DisplayName("可跟團清單:只帶摘要欄位,不帶參與者或品項")
    void openListCarriesSummaryFieldsOnly() {
        String owner = insertUser(userMapper, "open-fields", 0);
        insertOrder(orderMapper, "ord-open-3", owner, OrderStatus.OPEN);

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
        String owner = insertUser(userMapper, "open-total", 0);
        insertOrder(orderMapper, "ord-open-t1", owner, OrderStatus.OPEN);
        insertOrder(orderMapper, "ord-open-t2", owner, OrderStatus.SETTLED);

        long openTotal = orderMapper.getOpenOrdersWithStore(new Page<>(1, 1)).getTotal();
        long allTotal = orderMapper.getAllOrdersWithStore(new Page<>(1, 1)).getTotal();

        assertThat(openTotal).isLessThan(allTotal);
    }

    @Test
    @DisplayName("後台查詢:不限狀態,已結算的團也回")
    void adminListIncludesAllStatuses() {
        String owner = insertUser(userMapper, "admin-list", 0);
        insertOrder(orderMapper, "ord-admin-settled", owner, OrderStatus.SETTLED);

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

    @Test
    @DisplayName("條件式改狀態:DB 狀態在允許清單內才寫入,回傳 1")
    void updateStatusIfInMatches() {
        String owner = insertUser(userMapper, "cas-match", 0);
        insertOrder(orderMapper, "ord-cas-1", owner, OrderStatus.CLOSED);

        int affected = orderMapper.updateStatusIfIn(
                "ord-cas-1", OrderStatus.CANCELLED, List.of(OrderStatus.OPEN, OrderStatus.CLOSED));

        assertThat(affected).isEqualTo(1);
        assertThat(orderMapper.selectById("ord-cas-1").getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("條件式改狀態:DB 狀態已被改掉(例如已 SETTLED)→ 不寫入,回傳 0")
    void updateStatusIfInSkipsWhenStatusChanged() {
        String owner = insertUser(userMapper, "cas-miss", 0);
        insertOrder(orderMapper, "ord-cas-2", owner, OrderStatus.SETTLED);

        int affected = orderMapper.updateStatusIfIn(
                "ord-cas-2", OrderStatus.CANCELLED, List.of(OrderStatus.OPEN, OrderStatus.CLOSED));

        assertThat(affected).isZero();
        assertThat(orderMapper.selectById("ord-cas-2").getStatus()).isEqualTo(OrderStatus.SETTLED);
    }

    private List<String> openOrderIds() {
        return orderMapper.getOpenOrdersWithStore(new Page<>(1, 50))
                .getRecords().stream()
                .map(r -> String.valueOf(r.get("orderId")))
                .toList();
    }
}
