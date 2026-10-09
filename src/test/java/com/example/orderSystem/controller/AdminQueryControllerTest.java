package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.entity.Order;
import com.example.orderSystem.entity.Role;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.entity.UserRole;
import com.example.orderSystem.enums.OrderStatus;
import com.example.orderSystem.mapper.OrderMapper;
import com.example.orderSystem.mapper.RoleMapper;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.example.orderSystem.support.TestFixtures.insertOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminQueryControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtils jwtUtils;
    @Autowired UserMapper userMapper;
    @Autowired RoleMapper roleMapper;
    @Autowired UserRoleMapper userRoleMapper;
    @Autowired RbacCacheService rbacCacheService;
    @Autowired OrderMapper orderMapper;

    private static final String STAFF = "fx-adminquery-staff";
    private static final String ORDER_PREFIX = "ord-adminq-";

    private String adminToken;
    private String staffToken;

    @BeforeEach
    void setUp() {
        adminToken = jwtUtils.generateAccessToken("admin");
        ensureUser(STAFF);
        staffToken = jwtUtils.generateAccessToken(STAFF);
    }

    @AfterEach
    void restoreRoles() {
        setStaffRoles();
        orderMapper.delete(new QueryWrapper<Order>().likeRight("order_id", ORDER_PREFIX));
    }

    /**
     * 建立一張指定狀態、指定建立時間的訂單(與 STAFF 無關)。
     * 建立時間設在未來,確保它比共用資料庫裡其他測試留下的訂單都新,不受測試執行順序影響。
     */
    private String seedOrderCreatedAt(OrderStatus status, LocalDateTime createdAt) {
        String orderId = insertOrder(orderMapper, ORDER_PREFIX + UUID.randomUUID().toString().substring(0, 8),
                "bob", status);
        Order update = new Order();
        update.setOrderId(orderId);
        update.setCreatedAt(createdAt);
        orderMapper.updateById(update);
        return orderId;
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

    private void setStaffRoles(String... roleNames) {
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", STAFF));
        for (String name : roleNames) {
            Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("name", name));
            UserRole ur = new UserRole();
            ur.setUserId(STAFF);
            ur.setRoleId(role.getRoleId());
            userRoleMapper.insert(ur);
        }
        rbacCacheService.evictUserRoles(STAFF);
    }

    @Nested
    @DisplayName("GET /admin/orders/get_all_orders")
    class AdminOrders {

        @Test
        @DisplayName("客服 → 200,回傳不受參與範圍限制的全系統訂單")
        void customerServiceSeesAllOrders() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");
            // 已結算、STAFF 也沒參與的訂單 —— 客服仍看得到
            String settled = seedOrderCreatedAt(OrderStatus.SETTLED, LocalDateTime.now().plusDays(1));

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records", hasSize(greaterThanOrEqualTo(2))))
                    .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)))
                    .andExpect(jsonPath("$.records[?(@.orderId == '" + settled + "')]", hasSize(1)));
        }

        @Test
        @DisplayName("新的訂單在前:建立時間較晚者排在前面")
        void newestFirst() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");
            String older = seedOrderCreatedAt(OrderStatus.SETTLED, LocalDateTime.now().plusDays(2));
            String newer = seedOrderCreatedAt(OrderStatus.OPEN, LocalDateTime.now().plusDays(3));

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[0].orderId").value(newer))
                    .andExpect(jsonPath("$.records[1].orderId").value(older));
        }

        @Test
        @DisplayName("page=0 → 400,detail 指出 page")
        void pageZeroRejected() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .param("page", "0")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("page")));
        }

        @Test
        @DisplayName("size=0 → 400,detail 指出 size")
        void sizeZeroRejected() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .param("size", "0")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("size")));
        }

        @Test
        @DisplayName("size=21 超過上限 → 400(後台也不能一次撈光全系統訂單)")
        void sizeOverLimitRejected() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .param("size", "21")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("size")));
        }

        @Test
        @DisplayName("size=20 剛好是上限 → 200")
        void sizeAtLimitAccepted() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .param("size", "20")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(20));
        }

        @Test
        @DisplayName("無對應角色 → 403")
        void withoutRoleForbidden() throws Exception {
            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("權限不足"));
        }

        @Test
        @DisplayName("客服查任一訂單明細 → 200,不受參與者限制")
        void customerServiceReadsAnyOrderDetail() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");

            mockMvc.perform(get("/admin/orders/get_order_detail")
                            .param("orderId", "ord-002")     // 發起人是 bob
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.orderId").value("ord-002"));
        }

        @Test
        @DisplayName("撤銷客服角色後,同一顆 token 立即被擋")
        void revokingRoleTakesEffectImmediately() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");
            String sameToken = staffToken;

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .header("Authorization", "Bearer " + sameToken))
                    .andExpect(status().isOk());

            setStaffRoles();

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .header("Authorization", "Bearer " + sameToken))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /admin/transactions/get_user_transaction_record")
    class AdminTransactions {

        @Test
        @DisplayName("會計查指定使用者的交易紀錄 → 200")
        void accountantReadsAnyUserRecord() throws Exception {
            setStaffRoles("ACCOUNTANT");

            mockMvc.perform(get("/admin/transactions/get_user_transaction_record")
                            .param("userId", "bob")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("無對應角色 → 403")
        void withoutRoleForbidden() throws Exception {
            mockMvc.perform(get("/admin/transactions/get_user_transaction_record")
                            .param("userId", "bob")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("會計查不存在的使用者 → 404,不回空清單(對帳打錯字要看得出來)")
        void unknownUserIs404NotEmptyList() throws Exception {
            setStaffRoles("ACCOUNTANT");

            mockMvc.perform(get("/admin/transactions/get_user_transaction_record")
                            .param("userId", "no-such-user")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("客服沒有會計的資源授權 → 403(角色之間不互通)")
        void customerServiceCannotReadTransactions() throws Exception {
            setStaffRoles("CUSTOMER_SERVICE");

            mockMvc.perform(get("/admin/transactions/get_user_transaction_record")
                            .param("userId", "bob")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isForbidden());
        }
    }
}
