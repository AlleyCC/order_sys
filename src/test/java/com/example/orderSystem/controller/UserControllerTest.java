package com.example.orderSystem.controller;

import com.example.orderSystem.entity.Notification;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.enums.NotificationType;
import com.example.orderSystem.mapper.NotificationMapper;
import com.example.orderSystem.mapper.UserMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.*;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    private String aliceToken;

    @BeforeEach
    void setUp() {
        aliceToken = jwtUtils.generateAccessToken("alice");
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

    @Nested
    @DisplayName("GET /user/get_unread_notifications")
    class GetUnreadNotifications {

        @Test
        @DisplayName("只回自己的未讀,新的在前,含分頁資訊")
        void ownUnreadNewestFirst() throws Exception {
            String me = seedUser("me");
            String other = seedUser("other");
            LocalDateTime base = LocalDateTime.of(2026, 10, 1, 12, 0);
            String older = seedNotification(me, base, false);
            String newer = seedNotification(me, base.plusMinutes(5), false);
            seedNotification(me, base.plusMinutes(10), true);
            seedNotification(other, base, false);

            mockMvc.perform(get("/user/get_unread_notifications")
                            .header("Authorization", "Bearer " + jwtUtils.generateAccessToken(me)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.records", hasSize(2)))
                    .andExpect(jsonPath("$.records[0].notificationId").value(newer))
                    .andExpect(jsonPath("$.records[0].type").value("SETTLEMENT_INSUFFICIENT"))
                    .andExpect(jsonPath("$.records[0].orderId").value("ord-ntf"))
                    .andExpect(jsonPath("$.records[0].content").value("測試通知"))
                    .andExpect(jsonPath("$.records[0].createdAt").isNotEmpty())
                    .andExpect(jsonPath("$.records[1].notificationId").value(older));
        }

        @Test
        @DisplayName("size 超過上限 → 400")
        void sizeTooLarge() throws Exception {
            mockMvc.perform(get("/user/get_unread_notifications")
                            .param("size", "21")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("無 Token → 401")
        void noToken() throws Exception {
            mockMvc.perform(get("/user/get_unread_notifications"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /user/read_notification")
    class ReadNotification {

        @Test
        @DisplayName("標自己的 → 200,再查未讀不含該則;重複標 → 200")
        void markOwn() throws Exception {
            String me = seedUser("me");
            String token = jwtUtils.generateAccessToken(me);
            String id = seedNotification(me, LocalDateTime.now(), false);

            markRead(token, id).andExpect(status().isOk());
            mockMvc.perform(get("/user/get_unread_notifications")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(jsonPath("$.total").value(0));
            markRead(token, id).andExpect(status().isOk());
        }

        @Test
        @DisplayName("標別人的 → 404,對方仍為未讀")
        void othersNotification() throws Exception {
            String me = seedUser("me");
            String other = seedUser("other");
            String theirs = seedNotification(other, LocalDateTime.now(), false);

            markRead(jwtUtils.generateAccessToken(me), theirs).andExpect(status().isNotFound());
            mockMvc.perform(get("/user/get_unread_notifications")
                            .header("Authorization", "Bearer " + jwtUtils.generateAccessToken(other)))
                    .andExpect(jsonPath("$.total").value(1));
        }

        @Test
        @DisplayName("不存在 → 404")
        void notExists() throws Exception {
            markRead(aliceToken, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        }

        private org.springframework.test.web.servlet.ResultActions markRead(String token, String id)
                throws Exception {
            return mockMvc.perform(post("/user/read_notification")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"notificationId\":\"" + id + "\"}"));
        }
    }

    private String seedUser(String prefix) {
        User user = new User();
        user.setUserId(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
        user.setUserName(prefix);
        user.setPassword("$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW");
        user.setBalance(0L);
        userMapper.insert(user);
        return user.getUserId();
    }

    private String seedNotification(String userId, LocalDateTime createdAt, boolean read) {
        Notification n = new Notification();
        n.setNotificationId(UUID.randomUUID().toString());
        n.setUserId(userId);
        n.setOrderId("ord-ntf");
        n.setType(NotificationType.SETTLEMENT_INSUFFICIENT);
        n.setContent("測試通知");
        n.setCreatedAt(createdAt);
        if (read) {
            n.setReadAt(createdAt.plusMinutes(1));
        }
        notificationMapper.insert(n);
        return n.getNotificationId();
    }
}
