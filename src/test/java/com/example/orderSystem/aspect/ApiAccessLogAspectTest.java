package com.example.orderSystem.aspect;

import com.example.orderSystem.util.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ApiAccessLogAspectTest {

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
    private ObjectMapper objectMapper;

    private String aliceToken;

    @BeforeEach
    void setUp() {
        aliceToken = jwtUtils.generateAccessToken("alice", "employee");
    }

    @Nested
    @DisplayName("正常請求日誌")
    class SuccessLogging {

        @Test
        @DisplayName("公開 API → INFO log 含 method、URI、anonymous、耗時")
        void logsPublicEndpoint(CapturedOutput output) throws Exception {
            mockMvc.perform(get("/order/get_all_shops"))
                    .andExpect(status().isOk());

            assertThat(output.getAll()).contains("[API]");
            assertThat(output.getAll()).contains("GET");
            assertThat(output.getAll()).contains("/order/get_all_shops");
            assertThat(output.getAll()).contains("anonymous");
            assertThat(output.getAll()).contains("ms");
        }

        @Test
        @DisplayName("認證 API → INFO log 含 userId")
        void logsAuthenticatedEndpoint(CapturedOutput output) throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .header("Authorization", "Bearer " + aliceToken)
                            .param("page", "1")
                            .param("size", "10"))
                    .andExpect(status().isOk());

            assertThat(output.getAll()).contains("[API]");
            assertThat(output.getAll()).contains("userId=alice");
        }
    }

    @Nested
    @DisplayName("異常請求日誌")
    class ErrorLogging {

        @Test
        @DisplayName("不存在的訂單 → WARN log 含 exception 資訊")
        void logsExceptionEndpoint(CapturedOutput output) throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .header("Authorization", "Bearer " + aliceToken)
                            .param("orderId", "non-existent-order"))
                    .andExpect(status().isNotFound());

            assertThat(output.getAll()).contains("[API-ERROR]");
            assertThat(output.getAll()).contains("exception=");
            assertThat(output.getAll()).contains("ms");
        }
    }

    @Nested
    @DisplayName("敏感欄位脫敏")
    class SensitiveFieldMasking {

        @Test
        @DisplayName("login 請求 → password 不出現在 log 中")
        void masksPasswordInLog(CapturedOutput output) throws Exception {
            // Login will fail (wrong auth format) but AOP should still log
            mockMvc.perform(post("/login/create_token")
                            .header("Authorization", "JWT fakeEncryptedPassword")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":\"alice\"}"));

            String logOutput = output.getAll();
            // Should have [API] or [API-ERROR] log
            assertThat(logOutput).containsPattern("\\[API(-ERROR)?\\]");
            // Password-related content should be masked
            assertThat(logOutput).doesNotContain("fakeEncryptedPassword");
        }
    }
}
