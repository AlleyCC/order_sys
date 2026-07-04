package com.example.orderSystem.controller;

import com.example.orderSystem.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest              // 啟動完整 Spring 容器(含 Security filter chain)
@AutoConfigureMockMvc        // 提供 MockMvc,模擬真實 HTTP 請求
@Testcontainers              // 啟動下面的 Docker 容器(需要 Docker 開著!)
@ActiveProfiles("test")
class CategoryControllerTest {

    // 用真的 MySQL / Redis 容器,Flyway 會在啟動時把 V1~V4 migration 全跑一遍,
    // 所以 categories 表(含 UNIQUE 約束、generated column)都是「真的」。
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

    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        // 直接鑄造 token,不用真的走登入流程 —— 我們只想測 category 端點的授權
        adminToken = jwtUtils.generateAccessToken("admin", "admin");
        employeeToken = jwtUtils.generateAccessToken("alice", "employee");
    }

    @Nested
    @DisplayName("POST /category/create_category")
    class CreateCategory {

        // 注意:所有測試共用同一個容器/DB,所以每個測試用「不同名稱」避免互相污染
        @Test
        @DisplayName("管理員成功新增 → 201 + 回傳 DTO")
        void adminCreates() throws Exception {
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"飲料\",\"sortOrder\":1}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.categoryId").isNumber())   // id 由 DB 自動產生
                    .andExpect(jsonPath("$.name").value("飲料"))
                    .andExpect(jsonPath("$.sortOrder").value(1))
                    .andExpect(jsonPath("$.isDeleted").doesNotExist()); // 內部欄位不該外洩
        }

        @Test
        @DisplayName("同名重複 → 409")
        void duplicateName() throws Exception {
            // 第一次成功
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"主食\",\"sortOrder\":2}"))
                    .andExpect(status().isCreated());

            // 同名第二次 → 409
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"主食\",\"sortOrder\":3}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("分類名稱已存在"));
        }

        @Test
        @DisplayName("非管理員(employee)→ 403")
        void nonAdminForbidden() throws Exception {
            // @PreAuthorize("hasRole('ADMIN')") 擋下:已登入但角色不對 → 403
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + employeeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"甜點\",\"sortOrder\":1}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("無 Token → 401")
        void noTokenUnauthorized() throws Exception {
            // 沒登入 → Security 擋在門口 → 401(注意:跟 403 是不同層的攔截)
            mockMvc.perform(post("/category/create_category")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"炸物\",\"sortOrder\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("缺少 sortOrder → 400")
        void missingSortOrder() throws Exception {
            // @Valid + @NotNull 驗證失敗 → 400
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"湯品\"}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
