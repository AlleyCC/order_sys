package com.example.orderSystem.controller;

import com.example.orderSystem.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class OrderControllerTest {

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
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    private String aliceToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        aliceToken = jwtUtils.generateAccessToken("alice", "employee");
        adminToken = jwtUtils.generateAccessToken("admin", "admin");
    }

    // ========== GET /order/get_all_shops ==========

    @Nested
    @DisplayName("GET /order/get_all_shops")
    class GetAllShops {

        @Test
        @DisplayName("不需認證 → 200, 回傳店家列表")
        void returnsAllShops() throws Exception {
            mockMvc.perform(get("/order/get_all_shops"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].storeId").isNotEmpty())
                    .andExpect(jsonPath("$[0].storeName").isNotEmpty())
                    .andExpect(jsonPath("$[0].minOrderAmount").isNumber());
        }
    }

    // ========== GET /order/get_all_orders ==========

    @Nested
    @DisplayName("GET /order/get_all_orders")
    class GetAllOrders {

        @Test
        @DisplayName("有 Token → 200, 回傳訂單列表")
        void returnsAllOrders() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].orderId").isNotEmpty())
                    .andExpect(jsonPath("$[0].orderName").isNotEmpty())
                    .andExpect(jsonPath("$[0].deadline").isNotEmpty())
                    .andExpect(jsonPath("$[0].minOrderAmount").isNumber());
        }

        @Test
        @DisplayName("無 Token → 401")
        void noToken() throws Exception {
            mockMvc.perform(get("/order/get_all_orders"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== GET /order/get_order_detail ==========

    @Nested
    @DisplayName("GET /order/get_order_detail")
    class GetOrderDetail {

        @Test
        @DisplayName("存在的訂單 → 200, 含 orderItems")
        void returnsOrderWithItems() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .param("orderId", "ord-001")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value("ord-001"))
                    .andExpect(jsonPath("$.orderName").value("午餐鍋貼團"))
                    .andExpect(jsonPath("$.status").value("SETTLED"))
                    .andExpect(jsonPath("$.createdBy").value("alice"))
                    .andExpect(jsonPath("$.orderItems", hasSize(5)))
                    .andExpect(jsonPath("$.orderItems[0].userName").isNotEmpty());
        }

        @Test
        @DisplayName("不存在的訂單 → 404")
        void orderNotFound() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .param("orderId", "nonexistent")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("訂單不存在"));
        }

        @Test
        @DisplayName("缺少 orderId → 400")
        void missingOrderId() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== GET /order/get_user_account ==========

    @Nested
    @DisplayName("GET /order/get_user_account")
    class GetUserAccount {

        @Test
        @DisplayName("有交易記錄的使用者 → 200, 回傳 balance + availableBalance")
        void returnsBalance() throws Exception {
            mockMvc.perform(get("/order/get_user_account")
                            .param("userId", "alice")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(4895))
                    .andExpect(jsonPath("$.availableBalance").isNumber());
        }

        @Test
        @DisplayName("無交易記錄的使用者 → 200, balance=0")
        void noTransactions() throws Exception {
            mockMvc.perform(get("/order/get_user_account")
                            .param("userId", "admin")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(0))
                    .andExpect(jsonPath("$.availableBalance").value(0));
        }

        @Test
        @DisplayName("缺少 userId → 400")
        void missingUserId() throws Exception {
            mockMvc.perform(get("/order/get_user_account")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest());
        }
    }
}
