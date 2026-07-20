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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 容器、金鑰、@DynamicPropertySource 全部繼承自 AbstractIntegrationTest
class CategoryControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

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

    @Nested
    @DisplayName("POST /category/delete_category")
    class DeleteCategory {

        /** 刪除的小工具:只發請求不斷言,讓各測試自己接預期結果。 */
        private org.springframework.test.web.servlet.ResultActions deleteCategory(
                int id, int version, String token) throws Exception {
            return mockMvc.perform(post("/category/delete_category")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"categoryId\":" + id + ",\"version\":" + version + "}"));
        }

        @Test
        @DisplayName("管理員成功刪除 → 200 + message,且 get_categories 再也查不到")
        void adminDeletes() throws Exception {
            JsonNode created = createCategory("刪除-成功", 1);
            int id = created.get("categoryId").asInt();

            deleteCategory(id, 0, adminToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("成功刪除一筆分類"));

            // 驗證「真的刪了」:不信 API 的一面之詞,用查詢端點證明它從有效清單消失
            mockMvc.perform(get("/category/get_categories")
                            .param("categoryId", String.valueOf(id))
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("刪除後同名可重新建立 → 201(uk_name_active 的軟刪設計)")
        void recreateSameNameAfterDelete() throws Exception {
            JsonNode created = createCategory("刪除-重生", 1);
            deleteCategory(created.get("categoryId").asInt(), 0, adminToken)
                    .andExpect(status().isOk());

            // helper 內建 isCreated 斷言:這裡沒炸 = 同名成功重建
            createCategory("刪除-重生", 2);
        }

        @Test
        @DisplayName("分類不存在 → 404")
        void notFound() throws Exception {
            deleteCategory(999999, 0, adminToken)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("分類不存在"));
        }

        @Test
        @DisplayName("已軟刪過再刪一次 → 404(與 update 一致:不洩漏已刪資料)")
        void deleteTwiceNotFound() throws Exception {
            JsonNode created = createCategory("刪除-兩次", 1);
            int id = created.get("categoryId").asInt();

            deleteCategory(id, 0, adminToken).andExpect(status().isOk());

            // 第一次軟刪 version 已 0→1;就算帶「正確的」新 version,已軟刪就是 404
            deleteCategory(id, 1, adminToken).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("version 對不上 → 409(樂觀鎖擋下)")
        void staleVersionConflict() throws Exception {
            // create 出來的 version 固定是 0;client 卻拿 1 來刪 → 樂觀鎖打不中
            JsonNode created = createCategory("刪除-版本衝突", 1);
            int id = created.get("categoryId").asInt();

            deleteCategory(id, 1, adminToken)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("分類已被其他人修改,請重新載入"));
        }

        @Test
        @DisplayName("非管理員(employee)→ 403")
        void nonAdminForbidden() throws Exception {
            JsonNode created = createCategory("刪除-權限", 1);

            deleteCategory(created.get("categoryId").asInt(), 0, employeeToken)
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("無 Token → 401")
        void noTokenUnauthorized() throws Exception {
            mockMvc.perform(post("/category/delete_category")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":1,\"version\":0}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("缺 categoryId 或缺 version → 400")
        void missingFieldsRejected() throws Exception {
            mockMvc.perform(post("/category/delete_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))
                    .andExpect(status().isBadRequest());

            mockMvc.perform(post("/category/delete_category")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"categoryId\":1}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /category/get_categories")
    class GetCategories {

        // 提醒:全部測試共用同一個 DB,列表類斷言只能寫「結構性」的
        // (records 長度 <= size、total >= 自己建的筆數),不能假設 DB 是空的。

        @Test
        @DisplayName("不帶參數(employee 也可查)→ 200 + 統一分頁結構")
        void noParamsReturnsPagedList() throws Exception {
            createCategory("查詢-全部A", 1);
            createCategory("查詢-全部B", 2);

            mockMvc.perform(get("/category/get_categories")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isArray())
                    .andExpect(jsonPath("$.page").value(1))     // 預設第 1 頁
                    .andExpect(jsonPath("$.size").value(10))    // 預設每頁 10 筆
                    .andExpect(jsonPath("$.total").isNumber())
                    .andExpect(jsonPath("$.totalPages").isNumber());
        }

        @Test
        @DisplayName("帶 categoryId → 只回那一筆,欄位齊全(含 version,不含內部欄位)")
        void queryById() throws Exception {
            JsonNode created = createCategory("查詢-ID精準", 3);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(get("/category/get_categories")
                            .param("categoryId", String.valueOf(id))
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].categoryId").value(id))
                    .andExpect(jsonPath("$.records[0].name").value("查詢-ID精準"))
                    .andExpect(jsonPath("$.records[0].sortOrder").value(3))
                    .andExpect(jsonPath("$.records[0].version").value(0))
                    .andExpect(jsonPath("$.records[0].isDeleted").doesNotExist());
        }

        @Test
        @DisplayName("帶 categoryName(精確比對)→ 只回那一筆")
        void queryByName() throws Exception {
            JsonNode created = createCategory("查詢-名稱精準", 4);
            int id = created.get("categoryId").asInt();

            mockMvc.perform(get("/category/get_categories")
                            .param("categoryName", "查詢-名稱精準")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].categoryId").value(id));
        }

        @Test
        @DisplayName("categoryId 查無 → 200 + 空 records + total=0(不是 404)")
        void queryByIdNotFound() throws Exception {
            mockMvc.perform(get("/category/get_categories")
                            .param("categoryId", "999999")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isEmpty())
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("categoryName 查無 → 200 + 空 records + total=0")
        void queryByNameNotFound() throws Exception {
            mockMvc.perform(get("/category/get_categories")
                            .param("categoryName", "查詢-不存在的名字")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isEmpty())
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("categoryId 與 categoryName 同時帶 → 400")
        void bothParamsRejected() throws Exception {
            mockMvc.perform(get("/category/get_categories")
                            .param("categoryId", "1")
                            .param("categoryName", "飲料")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("categoryId 與 categoryName 不可同時使用"));
        }

        @Test
        @DisplayName("categoryName 是空白字串 → 視同沒帶(不當成過濾條件)")
        void blankNameTreatedAsAbsent() throws Exception {
            createCategory("查詢-空白名稱前置", 5);

            mockMvc.perform(get("/category/get_categories")
                            .param("categoryName", "  ")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").isNumber())
                    .andExpect(jsonPath("$.records").isNotEmpty());
        }

        @Test
        @DisplayName("分頁:size=2 → 每頁最多 2 筆、兩頁不重複、total 一致")
        void paginationPagesAreDisjoint() throws Exception {
            createCategory("查詢-分頁A", 21);
            createCategory("查詢-分頁B", 22);
            createCategory("查詢-分頁C", 23);
            createCategory("查詢-分頁D", 24);

            JsonNode page1 = queryPage(1, 2);
            JsonNode page2 = queryPage(2, 2);

            assertThat(page1.get("records").size()).isEqualTo(2);

            Set<Integer> idsPage1 = collectIds(page1);
            Set<Integer> idsPage2 = collectIds(page2);
            assertThat(idsPage1).doesNotContainAnyElementsOf(idsPage2); // 翻頁不能出現重複資料

            assertThat(page1.get("total").asLong()).isEqualTo(page2.get("total").asLong());
        }

        @Test
        @DisplayName("排序:sortOrder 升冪,同值再比 categoryId(分頁穩定的前提)")
        void sortedBySortOrderThenId() throws Exception {
            createCategory("查詢-排序B", 32);
            createCategory("查詢-排序A", 31);

            JsonNode res = queryPage(1, 100);
            JsonNode records = res.get("records");
            for (int i = 1; i < records.size(); i++) {
                int prevOrder = records.get(i - 1).get("sortOrder").asInt();
                int currOrder = records.get(i).get("sortOrder").asInt();
                assertThat(currOrder).isGreaterThanOrEqualTo(prevOrder);
                if (currOrder == prevOrder) {
                    assertThat(records.get(i).get("categoryId").asInt())
                            .isGreaterThan(records.get(i - 1).get("categoryId").asInt());
                }
            }
        }

        @Test
        @DisplayName("page 超過總頁數 → 200 + 空 records(total 照常)")
        void pageBeyondLastIsEmpty() throws Exception {
            createCategory("查詢-超頁", 41);

            mockMvc.perform(get("/category/get_categories")
                            .param("page", "99999")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isEmpty())
                    .andExpect(jsonPath("$.total").isNumber());
        }

        @Test
        @DisplayName("已軟刪的分類 → 查不到")
        void softDeletedExcluded() throws Exception {
            // 沒有 delete API,直接用 mapper 塞一筆已軟刪資料
            Category deleted = new Category();
            deleted.setName("查詢-已刪除");
            deleted.setSortOrder(1);
            deleted.setVersion(0);
            deleted.setIsDeleted(1);
            deleted.setCreatedBy("admin");
            categoryMapper.insert(deleted);

            mockMvc.perform(get("/category/get_categories")
                            .param("categoryName", "查詢-已刪除")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("無 Token → 401")
        void noTokenUnauthorized() throws Exception {
            mockMvc.perform(get("/category/get_categories"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("page=0 → 400")
        void pageZeroRejected() throws Exception {
            mockMvc.perform(get("/category/get_categories")
                            .param("page", "0")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("size 超過上限 100 → 400(不能靠灌大 size 繞過分頁)")
        void sizeOverLimitRejected() throws Exception {
            mockMvc.perform(get("/category/get_categories")
                            .param("size", "101")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("categoryId 不是數字 → 400(不能噴 500)")
        void nonNumericIdRejected() throws Exception {
            mockMvc.perform(get("/category/get_categories")
                            .param("categoryId", "abc")
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isBadRequest());
        }

        // ---- 小工具 ----

        private JsonNode queryPage(int page, int size) throws Exception {
            String body = mockMvc.perform(get("/category/get_categories")
                            .param("page", String.valueOf(page))
                            .param("size", String.valueOf(size))
                            .header("Authorization", "Bearer " + employeeToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body);
        }

        private Set<Integer> collectIds(JsonNode pageJson) {
            Set<Integer> ids = new HashSet<>();
            pageJson.get("records").forEach(r -> ids.add(r.get("categoryId").asInt()));
            return ids;
        }
    }
}
