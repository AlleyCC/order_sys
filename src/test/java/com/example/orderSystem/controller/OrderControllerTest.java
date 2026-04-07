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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    private ObjectMapper objectMapper;

    private String aliceToken;
    private String bobToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        aliceToken = jwtUtils.generateAccessToken("alice", "employee");
        bobToken = jwtUtils.generateAccessToken("bob", "employee");
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
        @DisplayName("預設分頁 → 200, 回傳 records + total + page 資訊")
        void returnsPagedOrders() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isArray())
                    .andExpect(jsonPath("$.records", hasSize(greaterThanOrEqualTo(2))))
                    .andExpect(jsonPath("$.records[0].orderId").isNotEmpty())
                    .andExpect(jsonPath("$.records[0].orderName").isNotEmpty())
                    .andExpect(jsonPath("$.total").isNumber())
                    .andExpect(jsonPath("$.current").value(1))
                    .andExpect(jsonPath("$.size").value(10));
        }

        @Test
        @DisplayName("指定 page=1, size=1 → 只回傳 1 筆")
        void customPageSize() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .param("page", "1")
                            .param("size", "1")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records", hasSize(1)))
                    .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)));
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

    // ========== POST /order/create_order ==========

    @Nested
    @DisplayName("POST /order/create_order")
    class CreateOrder {

        @Test
        @DisplayName("成功建立訂單 → 201")
        void success() throws Exception {
            mockMvc.perform(post("/order/create_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeId\":\"store001\",\"orderName\":\"測試團\",\"deadline\":\"2026-12-31 12:00:00\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.orderId").isNotEmpty())
                    .andExpect(jsonPath("$.storeId").value("store001"));
        }

        @Test
        @DisplayName("店家不存在 → 404")
        void storeNotFound() throws Exception {
            mockMvc.perform(post("/order/create_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeId\":\"bad\",\"orderName\":\"測試團\",\"deadline\":\"2026-12-31 12:00:00\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("缺少必填欄位 → 400")
        void missingFields() throws Exception {
            mockMvc.perform(post("/order/create_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeId\":\"store001\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== POST /order/create_user_order ==========

    @Nested
    @DisplayName("POST /order/create_user_order")
    class CreateUserOrder {

        @Test
        @DisplayName("成功下單 → 201")
        void success() throws Exception {
            // First create a new order with future deadline
            String createResp = mockMvc.perform(post("/order/create_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeId\":\"store001\",\"orderName\":\"下單測試團\",\"deadline\":\"2026-12-31 12:00:00\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String orderId = objectMapper.readTree(createResp).get("orderId").asText();

            // Then add item to it
            mockMvc.perform(post("/order/create_user_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\",\"menuId\":1,\"quantity\":1}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("下單成功"));
        }

        @Test
        @DisplayName("訂單不存在 → 404")
        void orderNotFound() throws Exception {
            mockMvc.perform(post("/order/create_user_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"nonexistent\",\"menuId\":5,\"quantity\":1}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("訂單已截止 (SETTLED) → 400")
        void orderNotOpen() throws Exception {
            // ord-001 is SETTLED in seed data
            mockMvc.perform(post("/order/create_user_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-001\",\"menuId\":1,\"quantity\":1}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("quantity <= 0 → 400")
        void invalidQuantity() throws Exception {
            mockMvc.perform(post("/order/create_user_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-002\",\"menuId\":5,\"quantity\":0}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== POST /order/cancel_order ==========

    @Nested
    @DisplayName("POST /order/cancel_order")
    class CancelOrder {

        @Test
        @DisplayName("非團主且非 admin → 403")
        void notOwner() throws Exception {
            // ord-002 created_by = bob, alice is not owner
            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-002\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("已結算訂單 → 400")
        void alreadySettled() throws Exception {
            // ord-001 is SETTLED, created_by = alice
            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-001\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== POST /order/pay_order ==========

    @Nested
    @DisplayName("POST /order/pay_order")
    class PayOrder {

        @Test
        @DisplayName("已結算訂單 → 409")
        void alreadySettled() throws Exception {
            mockMvc.perform(post("/order/pay_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-001\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("該訂單已結算，無法進行付款"));
        }

        @Test
        @DisplayName("OPEN 訂單 → 400")
        void stillOpen() throws Exception {
            mockMvc.perform(post("/order/pay_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-002\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("訂單不存在 → 404")
        void notFound() throws Exception {
            mockMvc.perform(post("/order/pay_order")
                            .header("Authorization", "Bearer " + aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"nonexistent\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
