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
class UserControllerTest {

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

    @BeforeEach
    void setUp() {
        aliceToken = jwtUtils.generateAccessToken("alice", "employee");
    }

    @Nested
    @DisplayName("GET /user/get_user_transaction_record")
    class GetTransactionRecord {

        @Test
        @DisplayName("有交易記錄 → 200, RECHARGE 正數, DEBIT 負數")
        void returnsRecords() throws Exception {
            mockMvc.perform(get("/user/get_user_transaction_record")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].transactionId").isNotEmpty())
                    .andExpect(jsonPath("$[0].amount").isNumber())
                    .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
        }

        @Test
        @DisplayName("無 Token → 401")
        void noToken() throws Exception {
            mockMvc.perform(get("/user/get_user_transaction_record"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
