package com.example.orderSystem.controller;

import com.example.orderSystem.entity.Category;
import com.example.orderSystem.mapper.CategoryMapper;
import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.util.JwtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 容器、金鑰、@DynamicPropertySource 全部繼承自 AbstractIntegrationTest
class CategoryControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtils.generateAccessToken("admin", "admin");
        employeeToken = jwtUtils.generateAccessToken("alice", "employee");
    }

    @Nested
    @DisplayName("POST /category/create_category")
    class CreateCategory {

        // 所有測試共用同一個容器/DB,所以每個測試用「不同名稱」避免互相污染
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
        @DisplayName("created_by 應等於當前登入者(驗證 @AuthenticationPrincipal 正確接上 principal)")
        void createdByComesFromPrincipal() throws Exception {
            // DTO 刻意不含 createdBy,所以直接查 DB 驗證稽核欄是否寫入登入者(admin)
            String resp = mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"稽核驗證\",\"sortOrder\":9}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            Integer categoryId = objectMapper.readTree(resp).get("categoryId").asInt();
            Category saved = categoryMapper.selectById(categoryId);
            assertThat(saved.getCreatedBy()).isEqualTo("admin");
        }

        @Test
        @DisplayName("同名重複 → 409")
        void duplicateName() throws Exception {
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"主食\",\"sortOrder\":2}"))
                    .andExpect(status().isCreated());

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
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + employeeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"甜點\",\"sortOrder\":1}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("無 Token → 401")
        void noTokenUnauthorized() throws Exception {
            mockMvc.perform(post("/category/create_category")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"炸物\",\"sortOrder\":1}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("缺少 sortOrder → 400")
        void missingSortOrder() throws Exception {
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"湯品\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("必須輸入 sortOrder"));
        }

        @Test
        @DisplayName("缺少 name → 400")
        void missingName() throws Exception {
            mockMvc.perform(post("/category/create_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sortOrder\":1}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("必須輸入 name"));
        }
    }
}
