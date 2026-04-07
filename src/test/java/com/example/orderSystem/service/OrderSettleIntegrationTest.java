package com.example.orderSystem.service;

import com.example.orderSystem.entity.Order;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OrderSettleIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("paypool")
            .withUsername("root")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    @DisplayName("settleOrder: OPEN 訂單 → CLOSED → SETTLED，餘額扣款正確")
    void settleSuccess() {
        // ord-002 is OPEN in seed data, has items for alice(55), bob(120), charlie(35)
        // alice balance=4895, bob=4860, charlie=2895
        orderService.settleOrder("ord-002");

        Order order = orderMapper.selectById("ord-002");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SETTLED);

        // Verify balances deducted
        assertThat(userMapper.selectById("alice").getBalance()).isEqualTo(4895 - 55);
        assertThat(userMapper.selectById("bob").getBalance()).isEqualTo(4860 - 120);
        assertThat(userMapper.selectById("charlie").getBalance()).isEqualTo(2895 - 35);
    }

    @Test
    @DisplayName("settleOrder: 非 OPEN 訂單 → 跳過，不改狀態")
    void skipNonOpen() {
        // ord-001 is SETTLED
        orderService.settleOrder("ord-001");

        Order order = orderMapper.selectById("ord-001");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SETTLED);
    }
}
