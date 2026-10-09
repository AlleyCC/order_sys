package com.example.orderSystem.mapper;

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

import static com.example.orderSystem.support.TestFixtures.insertItem;
import static com.example.orderSystem.support.TestFixtures.insertOrder;
import static com.example.orderSystem.support.TestFixtures.insertUser;
import static org.assertj.core.api.Assertions.assertThat;

@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MyBatisPlusConfig.class)
@ActiveProfiles("test")
class OrderItemMapperTest {

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

    @Autowired OrderItemMapper orderItemMapper;
    @Autowired OrderMapper orderMapper;
    @Autowired UserMapper userMapper;

    @Test
    @DisplayName("凍結金額:OPEN、CLOSED、FAILED 都要凍結(CLOSED 是已截止、待扣款)")
    void frozenAmountIncludesOpenClosedAndFailed() {
        String user = insertUser(userMapper, "frz-unpaid", 0);
        seedOrderWithItem("ord-frz-open", user, OrderStatus.OPEN, 10);
        seedOrderWithItem("ord-frz-closed", user, OrderStatus.CLOSED, 20);
        seedOrderWithItem("ord-frz-failed", user, OrderStatus.FAILED, 40);

        assertThat(orderItemMapper.getFrozenAmount(user)).isEqualTo(70L);
    }

    @Test
    @DisplayName("凍結金額:SETTLED 已扣款、CANCELLED 已取消,都不凍結")
    void frozenAmountExcludesSettledAndCancelled() {
        String user = insertUser(userMapper, "frz-done", 0);
        seedOrderWithItem("ord-frz-settled", user, OrderStatus.SETTLED, 10);
        seedOrderWithItem("ord-frz-cancelled", user, OrderStatus.CANCELLED, 20);

        assertThat(orderItemMapper.getFrozenAmount(user)).isZero();
    }

    private void seedOrderWithItem(String orderId, String userId, OrderStatus status, int unitPrice) {
        insertOrder(orderMapper, orderId, userId, status);
        insertItem(orderItemMapper, orderId, userId, unitPrice);
    }
}
