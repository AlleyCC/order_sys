package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.OrderItem;
import com.example.orderSystem.entity.Role;
import com.example.orderSystem.entity.Store;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.entity.UserRole;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderItemMapper;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.example.orderSystem.mapper.StoreMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.mapper.UserRoleMapper;
import com.example.orderSystem.service.RbacCacheService;
import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.util.JwtUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 容器、金鑰、@DynamicPropertySource 全部繼承自 AbstractIntegrationTest
class OrderControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderItemMapper orderItemMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private RbacCacheService rbacCacheService;

    @Autowired
    private StoreMapper storeMapper;

    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private String rescuerToken;

    private static final String RESCUER = "fx-orderctl-rescuer";
    private static final String RESCUE_PREFIX = "ord-rescue-";
    private static final String PAGING_STORE_PREFIX = "分頁測試店-";

    @BeforeEach
    void setUp() {
        aliceToken = jwtUtils.generateAccessToken("alice");
        bobToken = jwtUtils.generateAccessToken("bob");
        adminToken = jwtUtils.generateAccessToken("admin");
        ensureUser(RESCUER);
        rescuerToken = jwtUtils.generateAccessToken(RESCUER);
    }

    @AfterEach
    void cleanUpRescueFixtures() {
        setRescuerRoles();
        orderItemMapper.delete(new QueryWrapper<OrderItem>().likeRight("order_id", RESCUE_PREFIX));
        orderMapper.delete(new QueryWrapper<Order>().likeRight("order_id", RESCUE_PREFIX));
        storeMapper.delete(new QueryWrapper<Store>().likeRight("store_name", PAGING_STORE_PREFIX));
    }

    private void seedStore(String storeId, String storeName, int minOrderAmount) {
        Store store = new Store();
        store.setStoreId(storeId);
        store.setStoreName(storeName);
        store.setMinOrderAmount(minOrderAmount);
        storeMapper.insert(store);
    }

    private void ensureUser(String userId) {
        if (userMapper.selectById(userId) != null) {
            return;
        }
        User user = new User();
        user.setUserId(userId);
        user.setUserName(userId);
        user.setPassword("$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW");
        user.setBalance(0L);
        userMapper.insert(user);
    }

    private void setRescuerRoles(String... roleNames) {
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", RESCUER));
        for (String name : roleNames) {
            Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("name", name));
            UserRole ur = new UserRole();
            ur.setUserId(RESCUER);
            ur.setRoleId(role.getRoleId());
            userRoleMapper.insert(ur);
        }
        rbacCacheService.evictUserRoles(RESCUER);
    }

    private String seedOpenOrder(String createdBy) {
        Order order = new Order();
        order.setOrderId(RESCUE_PREFIX + UUID.randomUUID().toString().substring(0, 8));
        order.setStoreId("store001");
        order.setCreatedBy(createdBy);
        order.setOrderName("救援情境測試團");
        order.setStatus(OrderStatus.OPEN);
        order.setDeadline(LocalDateTime.now().plusHours(1));
        orderMapper.insert(order);
        return order.getOrderId();
    }

    private Integer seedItem(String orderId, String userId) {
        OrderItem item = new OrderItem();
        item.setOrderId(orderId);
        item.setUserId(userId);
        item.setMenuId(1);
        item.setProductName("救援情境品項");
        item.setUnitPrice(50);
        item.setQuantity(1);
        orderItemMapper.insert(item);
        return item.getItemId();
    }

    // ========== GET /order/get_all_shops ==========

    @Nested
    @DisplayName("GET /order/get_all_shops")
    class GetAllShops {

        @Test
        @DisplayName("無 Token → 401(已移出白名單)")
        void noTokenUnauthorized() throws Exception {
            mockMvc.perform(get("/order/get_all_shops"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("零角色的一般使用者 → 200(不需特定角色)")
        void zeroRoleUserCanList() throws Exception {
            setRescuerRoles();

            mockMvc.perform(get("/order/get_all_shops")
                            .header("Authorization", "Bearer " + rescuerToken))
                    .andExpect(status().isOk());
        }


        @Test
        @DisplayName("回傳分頁物件:records + page + size + total + totalPages")
        void returnsPageObject() throws Exception {
            mockMvc.perform(get("/order/get_all_shops")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records").isArray())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.size").value(10))
                    .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(3)))
                    .andExpect(jsonPath("$.totalPages").isNumber());
        }

        @Test
        @DisplayName("每筆店家恰好只有 storeId / storeName / minOrderAmount(不外洩電話、地址、時間戳)")
        void recordsCarrySummaryFieldsOnly() throws Exception {
            JsonNode first = queryShops(Map.of()).get("records").get(0);

            Set<String> fields = new HashSet<>();
            first.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("storeId", "storeName", "minOrderAmount");
        }


        @Test
        @DisplayName("依低消由低到高:seed 的三家店順序為 250 → 300 → 350")
        void sortedByMinOrderAmount() throws Exception {
            List<String> ids = storeIds(queryShops(Map.of("size", "20")));

            List<String> seedOrder = ids.stream()
                    .filter(List.of("store001", "store002", "store003")::contains)
                    .toList();
            assertThat(seedOrder).containsExactly("store002", "store003", "store001");
        }

        @Test
        @DisplayName("同低消時逐頁查完:不重複、不遺漏,且同值依 storeId 排序")
        void sameAmountPagesAreStable() throws Exception {
            List<String> inserted = List.of("pg-test-3", "pg-test-1", "pg-test-5", "pg-test-2", "pg-test-4");
            inserted.forEach(id -> seedStore(id, PAGING_STORE_PREFIX + id, 0));

            List<String> collected = new ArrayList<>();
            for (int page = 1; page <= 3; page++) {
                JsonNode res = queryShops(Map.of(
                        "storeName", PAGING_STORE_PREFIX, "page", String.valueOf(page), "size", "2"));
                assertThat(res.get("total").asLong()).isEqualTo(5);
                assertThat(res.get("totalPages").asLong()).isEqualTo(3);
                collected.addAll(storeIds(res));
            }

            assertThat(collected).containsExactly("pg-test-1", "pg-test-2", "pg-test-3", "pg-test-4", "pg-test-5");
        }

        @Test
        @DisplayName("page 超過總頁數 → 200 + 空 records(total 照常)")
        void pageBeyondLastIsEmpty() throws Exception {
            long total = queryShops(Map.of()).get("total").asLong();

            JsonNode res = queryShops(Map.of("page", "99999"));
            assertThat(res.get("records")).isEmpty();
            assertThat(res.get("total").asLong()).isEqualTo(total);
        }

        @Test
        @DisplayName("size=21 超過上限 → 400")
        void sizeOverLimitRejected() throws Exception {
            mockMvc.perform(get("/order/get_all_shops")
                            .param("size", "21")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("size")));
        }


        @Test
        @DisplayName("storeName=八方 → 只回八方雲集")
        void filterByPartialName() throws Exception {
            JsonNode res = queryShops(Map.of("storeName", "八方"));

            assertThat(res.get("total").asLong()).isEqualTo(1);
            assertThat(res.get("records").get(0).get("storeName").asText()).isEqualTo("八方雲集(烏日店)");
        }

        @Test
        @DisplayName("storeName 空字串或只有空白 → 視同沒帶")
        void blankNameTreatedAsAbsent() throws Exception {
            long total = queryShops(Map.of()).get("total").asLong();

            assertThat(queryShops(Map.of("storeName", "")).get("total").asLong()).isEqualTo(total);
            assertThat(queryShops(Map.of("storeName", "   ")).get("total").asLong()).isEqualTo(total);
        }

        @Test
        @DisplayName("storeName=% → 當一般字元,不是萬用字元(否則會查出全部)")
        void percentIsLiteral() throws Exception {
            JsonNode res = queryShops(Map.of("storeName", "%"));

            assertThat(res.get("records")).isEmpty();
            assertThat(res.get("total").asLong()).isZero();
        }

        @Test
        @DisplayName("storeName=_ → 當一般字元,不是萬用字元(否則任何名稱都會中)")
        void underscoreIsLiteral() throws Exception {
            JsonNode res = queryShops(Map.of("storeName", "_"));

            assertThat(res.get("records")).isEmpty();
            assertThat(res.get("total").asLong()).isZero();
        }

        @Test
        @DisplayName("無符合結果 → 200,total=0,totalPages=0")
        void noMatchReturnsEmptyPage() throws Exception {
            JsonNode res = queryShops(Map.of("storeName", "不存在的店名xyz"));

            assertThat(res.get("records")).isEmpty();
            assertThat(res.get("total").asLong()).isZero();
            assertThat(res.get("totalPages").asLong()).isZero();
        }


        private JsonNode queryShops(Map<String, String> params) throws Exception {
            var request = get("/order/get_all_shops").header("Authorization", "Bearer " + aliceToken);
            params.forEach(request::param);
            String body = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body);
        }

        private List<String> storeIds(JsonNode pageJson) {
            List<String> ids = new ArrayList<>();
            pageJson.get("records").forEach(r -> ids.add(r.get("storeId").asText()));
            return ids;
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

        @Test
        @DisplayName("page=0 → 400,detail 指出 page")
        void pageZeroRejected() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .param("page", "0")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("page")));
        }

        @Test
        @DisplayName("size=0 → 400,detail 指出 size")
        void sizeZeroRejected() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .param("size", "0")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("size")));
        }

        @Test
        @DisplayName("size=21 超過上限 → 400(不能靠灌大 size 一次撈光)")
        void sizeOverLimitRejected() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .param("size", "21")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("size")));
        }

        @Test
        @DisplayName("size=20 剛好是上限 → 200")
        void sizeAtLimitAccepted() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .param("size", "20")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(20));
        }

        @Test
        @DisplayName("探索:沒參與任何訂單的人也看得到可跟的團")
        void nonParticipantCanStillDiscoverOpenOrders() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records", hasSize(greaterThanOrEqualTo(1))));
        }

        @Test
        @DisplayName("清單只帶摘要欄位,不洩漏參與者或品項")
        void listCarriesSummaryFieldsOnly() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].orderId").exists())
                    .andExpect(jsonPath("$.records[0].orderItems").doesNotExist())
                    .andExpect(jsonPath("$.records[0].createdBy").doesNotExist());
        }

        @Test
        @DisplayName("已結算的團不出現在可跟團清單")
        void settledOrderNotListed() throws Exception {
            mockMvc.perform(get("/order/get_all_orders")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[?(@.orderId == 'ord-001')]", hasSize(0)));
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
        @DisplayName("不存在的訂單 → 403,與「不是你的訂單」同一種回應(不洩漏存在性)")
        void unknownOrderIsIndistinguishableFromForbidden() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .param("orderId", "nonexistent")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("無權限查看此訂單"));

            mockMvc.perform(get("/order/get_order_detail")
                            .param("orderId", "ord-001")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("無權限查看此訂單"));
        }

        @Test
        @DisplayName("缺少 orderId → 400")
        void missingOrderId() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("下單者(非發起人)→ 200")
        void participantCanRead() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .param("orderId", "ord-001")
                            .header("Authorization", "Bearer " + bobToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value("ord-001"));
        }

        @Test
        @DisplayName("非參與者 → 403,且不洩漏訂單任何欄位(超管也要走後台端點)")
        void nonParticipantForbidden() throws Exception {
            mockMvc.perform(get("/order/get_order_detail")
                            .param("orderId", "ord-001")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.orderId").doesNotExist())
                    .andExpect(jsonPath("$.orderName").doesNotExist())
                    .andExpect(jsonPath("$.orderItems").doesNotExist());
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
                            .header("Authorization", "Bearer " + aliceToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(4895))
                    .andExpect(jsonPath("$.availableBalance").isNumber());
        }

        @Test
        @DisplayName("無交易記錄的使用者 → 200, balance=0")
        void noTransactions() throws Exception {
            mockMvc.perform(get("/order/get_user_account")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(0))
                    .andExpect(jsonPath("$.availableBalance").value(0));
        }

        @Test
        @DisplayName("帶他人 userId → 忽略該參數,仍回本人餘額")
        void otherUserIdParamIsIgnored() throws Exception {
            mockMvc.perform(get("/order/get_user_account")
                            .param("userId", "alice")        // 試圖查 alice
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balance").value(0));   // 仍是 admin 自己的餘額
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

    // ========== POST /order/delete_user_order ==========

    @Nested
    @DisplayName("POST /order/delete_user_order")
    class DeleteUserOrder {

        @Test
        @DisplayName("客服刪除他人團中他人的品項 → 200,品項確實被刪除")
        void customerServiceDeletesOthersItem() throws Exception {
            String orderId = seedOpenOrder("bob");
            Integer itemId = seedItem(orderId, "charlie");
            setRescuerRoles("CUSTOMER_SERVICE");

            mockMvc.perform(post("/order/delete_user_order")
                            .header("Authorization", "Bearer " + rescuerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\",\"itemId\":\"" + itemId + "\"}"))
                    .andExpect(status().isOk());

            assertThat(orderItemMapper.selectById(itemId)).isNull();
        }

        @Test
        @DisplayName("無角色者刪除他人團中他人的品項 → 403,品項仍在")
        void plainUserCannotDeleteOthersItem() throws Exception {
            String orderId = seedOpenOrder("bob");
            Integer itemId = seedItem(orderId, "charlie");

            mockMvc.perform(post("/order/delete_user_order")
                            .header("Authorization", "Bearer " + rescuerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\",\"itemId\":\"" + itemId + "\"}"))
                    .andExpect(status().isForbidden());

            assertThat(orderItemMapper.selectById(itemId)).isNotNull();
        }

        @Test
        @DisplayName("品項擁有者刪除自己的品項 → 200(端點不登記,一般使用者照常可用)")
        void itemOwnerDeletesOwnItem() throws Exception {
            String orderId = seedOpenOrder("bob");
            Integer itemId = seedItem(orderId, RESCUER);

            mockMvc.perform(post("/order/delete_user_order")
                            .header("Authorization", "Bearer " + rescuerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\",\"itemId\":\"" + itemId + "\"}"))
                    .andExpect(status().isOk());

            assertThat(orderItemMapper.selectById(itemId)).isNull();
        }
    }

    // ========== POST /order/cancel_order ==========

    @Nested
    @DisplayName("POST /order/cancel_order")
    class CancelOrder {

        @Test
        @DisplayName("非開團者、且無客服/超管角色 → 403,訂單狀態不變")
        void notOwnerWithoutRescueRole() throws Exception {
            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + rescuerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"ord-002\"}"))
                    .andExpect(status().isForbidden());

            assertThat(orderMapper.selectById("ord-002").getStatus()).isEqualTo(OrderStatus.OPEN);
        }

        @Test
        @DisplayName("客服取消他人發起的 OPEN 訂單 → 200,狀態轉 CANCELLED")
        void customerServiceCancelsOthersOrder() throws Exception {
            String orderId = seedOpenOrder("bob");
            setRescuerRoles("CUSTOMER_SERVICE");

            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + rescuerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\"}"))
                    .andExpect(status().isOk());

            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("超管取消他人發起的 OPEN 訂單 → 200")
        void superAdminCancelsOthersOrder() throws Exception {
            String orderId = seedOpenOrder("bob");

            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + orderId + "\"}"))
                    .andExpect(status().isOk());

            assertThat(orderMapper.selectById(orderId).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("撤銷客服角色後,同一顆 token 的下一個請求立即失效")
        void revokingCustomerServiceTakesEffectImmediately() throws Exception {
            String first = seedOpenOrder("bob");
            String second = seedOpenOrder("bob");
            setRescuerRoles("CUSTOMER_SERVICE");
            String sameToken = rescuerToken;

            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + sameToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + first + "\"}"))
                    .andExpect(status().isOk());

            setRescuerRoles();            // 拔光角色

            mockMvc.perform(post("/order/cancel_order")
                            .header("Authorization", "Bearer " + sameToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orderId\":\"" + second + "\"}"))
                    .andExpect(status().isForbidden());

            assertThat(orderMapper.selectById(second).getStatus()).isEqualTo(OrderStatus.OPEN);
        }

        @Test
        @DisplayName("開團者取消自己已結算的團 → 400(業務規則,不是 403)")
        void alreadySettled() throws Exception {
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
