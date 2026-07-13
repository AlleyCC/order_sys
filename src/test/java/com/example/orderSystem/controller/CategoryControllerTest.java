package com.example.orderSystem.controller;

import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.util.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtils.generateAccessToken("admin", "admin");
        employeeToken = jwtUtils.generateAccessToken("alice", "employee");
    }

    /** 用 create API 建一筆分類,回傳它的 JSON(含 categoryId、version),給 update 測試當前置資料。 */
    private JsonNode createCategory(String name, int sortOrder) throws Exception {
        String body = mockMvc.perform(post("/category/create_category")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"sortOrder\":" + sortOrder + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
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
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PATCH /category/update_category")
    class UpdateCategory {

        @Test
        @DisplayName("管理員改名稱+排序 → 200 + 值更新 + version 進位")
        void adminUpdatesNameAndOrder() throws Exception {
            JsonNode created = createCategory("飲品", 1);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"name\":\"氣泡水\",\"sortOrder\":5}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("氣泡水"))
                    .andExpect(jsonPath("$.sortOrder").value(5))
                    .andExpect(jsonPath("$.version").value(1)); // 樂觀鎖:成功一次就 +1
        }

        @Test
        @DisplayName("只送 sortOrder(部分更新)→ 200,name 不變")
        void partialUpdateOnlySortOrder() throws Exception {
            JsonNode created = createCategory("主餐", 1);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"sortOrder\":9}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("主餐")) // 沒送 name → 不動
                    .andExpect(jsonPath("$.sortOrder").value(9));
        }

        @Test
        @DisplayName("改成跟自己現在一樣的名字 → 200(唯一性排除自己)")
        void renameToSameName() throws Exception {
            JsonNode created = createCategory("湯", 1);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"name\":\"湯\",\"sortOrder\":2}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("改後名稱撞到別的有效分類 → 409")
        void duplicateName() throws Exception {
            createCategory("沙拉", 1);
            JsonNode other = createCategory("甜品", 2);
            int id = other.get("categoryId").asInt();

            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"name\":\"沙拉\",\"sortOrder\":2}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("分類名稱已存在"));
        }

        @Test
        @DisplayName("帶過期 version(兩個管理員同時改)→ 409")
        void staleVersionConflict() throws Exception {
            JsonNode created = createCategory("炸物", 1);
            int id = created.get("categoryId").asInt();

            // Admin1 先成功改一次:version 0 → 1
            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"sortOrder\":3}"))
                    .andExpect(status().isOk());

            // Admin2 手上還是舊的 version 0 → 打不中,偵測到衝突
            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"sortOrder\":7}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("分類已被其他人修改,請重新載入"));
        }

        @Test
        @DisplayName("categoryId 不存在 → 404")
        void notFound() throws Exception {
            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":999999,\"version\":0,\"name\":\"不存在\",\"sortOrder\":1}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("name 送空字串 → 400")
        void blankName() throws Exception {
            JsonNode created = createCategory("餅乾", 1);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"name\":\"\",\"sortOrder\":1}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("非管理員(employee)→ 403")
        void nonAdminForbidden() throws Exception {
            JsonNode created = createCategory("麵包", 1);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(patch("/category/update_category")
                            .header("Authorization", "Bearer " + employeeToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":" + id + ",\"version\":0,\"name\":\"吐司\",\"sortOrder\":1}"))
                    .andExpect(status().isForbidden());
        }
    }
}
