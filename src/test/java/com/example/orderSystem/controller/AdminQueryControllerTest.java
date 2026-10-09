package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.orderSystem.entity.Role;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.entity.User;
import com.example.orderSystem.entity.UserRole;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.mapper.RoleMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.mapper.UserRoleMapper;
import com.example.orderSystem.payment.MockPayChannel;
import com.example.orderSystem.service.RbacCacheService;
import com.example.orderSystem.service.RechargeService;
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
import java.util.List;
import java.util.UUID;

import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired TransactionMapper transactionMapper;
    @Autowired RechargeOrderMapper rechargeOrderMapper;
    @Autowired RechargeService rechargeService;
    @Autowired MockPayChannel mockPayChannel;

    private static final String STAFF = "fx-adminquery-staff";

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

            mockMvc.perform(get("/admin/orders/get_all_orders")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records", hasSize(greaterThanOrEqualTo(2))))
                    .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)))
                    .andExpect(jsonPath("$.records[?(@.orderId == 'ord-001')]", hasSize(1)));
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

    @Nested
    @DisplayName("GET /admin/transactions/get_all_transactions")
    class AllTransactions {

        @Test
        @DisplayName("會計不帶篩選 → 200,全體使用者第一頁 + 總筆數,每筆帶對帳所需欄位")
        void accountantReadsAllUsers() throws Exception {
            setStaffRoles("ACCOUNTANT");

            mockMvc.perform(get("/admin/transactions/get_all_transactions")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(2)))
                    .andExpect(jsonPath("$.records[0].transactionId").exists())
                    .andExpect(jsonPath("$.records[0].userId").exists())
                    .andExpect(jsonPath("$.records[0].type").exists())
                    .andExpect(jsonPath("$.records[0].closingBalance").exists())
                    .andExpect(jsonPath("$.records[0].createdBy").exists());
        }

        @Test
        @DisplayName("依 type + userId 篩選:儲值為正並帶 rechargeId,扣款為負並帶 orderId")
        void filtersByTypeAndUser() throws Exception {
            setStaffRoles("ACCOUNTANT");
            String userId = seedUser(userMapper, "audit", 0);
            String rechargeId = paidRecharge(userId, 500);
            insertTxn(userId, TradeType.DEBIT, 120, LocalDateTime.of(2020, 1, 15, 12, 0));

            mockMvc.perform(get("/admin/transactions/get_all_transactions")
                            .param("type", "RECHARGE")
                            .param("userId", userId)
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].type").value("RECHARGE"))
                    .andExpect(jsonPath("$.records[0].amount").value(500))
                    .andExpect(jsonPath("$.records[0].rechargeId").value(rechargeId));

            mockMvc.perform(get("/admin/transactions/get_all_transactions")
                            .param("type", "DEBIT")
                            .param("userId", userId)
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].amount").value(-120))
                    .andExpect(jsonPath("$.records[0].orderId").value("ord-001"));
        }

        @Test
        @DisplayName("依日期區間篩選:起訖日都包含在內,由新到舊")
        void filtersByDateRange() throws Exception {
            setStaffRoles("ACCOUNTANT");
            String userId = seedUser(userMapper, "audit", 0);
            insertTxn(userId, TradeType.DEBIT, 10, LocalDateTime.of(2020, 1, 14, 23, 59));
            insertTxn(userId, TradeType.DEBIT, 20, LocalDateTime.of(2020, 1, 15, 0, 0));
            insertTxn(userId, TradeType.DEBIT, 30, LocalDateTime.of(2020, 1, 16, 23, 59));
            insertTxn(userId, TradeType.DEBIT, 40, LocalDateTime.of(2020, 1, 17, 0, 0));

            mockMvc.perform(get("/admin/transactions/get_all_transactions")
                            .param("userId", userId)
                            .param("from", "2020-01-15")
                            .param("to", "2020-01-16")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.records[0].amount").value(-30))
                    .andExpect(jsonPath("$.records[1].amount").value(-20));
        }

        @Test
        @DisplayName("size=21 / page=0 → 400")
        void invalidPagingRejected() throws Exception {
            setStaffRoles("ACCOUNTANT");

            mockMvc.perform(get("/admin/transactions/get_all_transactions")
                            .param("size", "21")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/admin/transactions/get_all_transactions")
                            .param("page", "0")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /admin/transactions/get_recharge_orders")
    class RechargeOrders {

        @Test
        @DisplayName("依狀態 + userId 篩選:只回該狀態的儲值單")
        void filtersByStatus() throws Exception {
            setStaffRoles("ACCOUNTANT");
            String userId = seedUser(userMapper, "audit", 0);
            String closed = rechargeService.createRechargeOrder(userId, 300).getRechargeId();
            rechargeOrderMapper.closeIfWaiting(closed);
            paidRecharge(userId, 500);

            mockMvc.perform(get("/admin/transactions/get_recharge_orders")
                            .param("status", "CLOSED")
                            .param("userId", userId)
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].rechargeId").value(closed))
                    .andExpect(jsonPath("$.records[0].status").value("CLOSED"))
                    .andExpect(jsonPath("$.records[0].userId").value(userId));
        }
    }

    @Nested
    @DisplayName("POST /admin/transactions/sync_recharge_order")
    class ManualSync {

        @Test
        @DisplayName("CLOSED 但通道其實已付款 → SUCCESS,流水的操作者是會計")
        void closedButPaidIsCredited() throws Exception {
            setStaffRoles("ACCOUNTANT");
            String userId = seedUser(userMapper, "audit", 0);
            String id = rechargeService.createRechargeOrder(userId, 500).getRechargeId();
            mockPayChannel.markPaid(id);
            rechargeOrderMapper.closeIfWaiting(id);     // 本地已關單,通道其實收到錢了

            mockMvc.perform(post("/admin/transactions/sync_recharge_order")
                            .param("rechargeId", id)
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));

            assertThat(userMapper.selectById(userId).getBalance()).isEqualTo(500L);
            assertThat(rechargeTxns(id)).singleElement()
                    .extracting(Transaction::getCreatedBy).isEqualTo(STAFF);
        }

        @Test
        @DisplayName("通道回覆未付款 → 狀態不變")
        void unpaidStaysClosed() throws Exception {
            setStaffRoles("ACCOUNTANT");
            String userId = seedUser(userMapper, "audit", 0);
            String id = rechargeService.createRechargeOrder(userId, 500).getRechargeId();
            rechargeOrderMapper.closeIfWaiting(id);

            mockMvc.perform(post("/admin/transactions/sync_recharge_order")
                            .param("rechargeId", id)
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLOSED"));

            assertThat(userMapper.selectById(userId).getBalance()).isZero();
        }

        @Test
        @DisplayName("已入帳的單再對一次 → 仍是 SUCCESS,餘額與流水不變")
        void alreadyPaidUnchanged() throws Exception {
            setStaffRoles("ACCOUNTANT");
            String userId = seedUser(userMapper, "audit", 0);
            String id = paidRecharge(userId, 500);

            mockMvc.perform(post("/admin/transactions/sync_recharge_order")
                            .param("rechargeId", id)
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"));

            assertThat(userMapper.selectById(userId).getBalance()).isEqualTo(500L);
            assertThat(rechargeTxns(id)).hasSize(1);
        }

        @Test
        @DisplayName("查無此單 → 404")
        void unknownIs404() throws Exception {
            setStaffRoles("ACCOUNTANT");

            mockMvc.perform(post("/admin/transactions/sync_recharge_order")
                            .param("rechargeId", "no-such")
                            .header("Authorization", "Bearer " + staffToken))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("對帳端點的授權")
    class AuditAuthorization {

        @Test
        @DisplayName("沒有角色 / 只有客服 → 三個端點都 403,且對單不異動資料")
        void othersForbidden() throws Exception {
            String userId = seedUser(userMapper, "audit", 0);
            String id = rechargeService.createRechargeOrder(userId, 500).getRechargeId();
            mockPayChannel.markPaid(id);
            rechargeOrderMapper.closeIfWaiting(id);

            for (String[] roles : new String[][]{{}, {"CUSTOMER_SERVICE"}}) {
                setStaffRoles(roles);

                mockMvc.perform(get("/admin/transactions/get_all_transactions")
                                .header("Authorization", "Bearer " + staffToken))
                        .andExpect(status().isForbidden());
                mockMvc.perform(get("/admin/transactions/get_recharge_orders")
                                .header("Authorization", "Bearer " + staffToken))
                        .andExpect(status().isForbidden());
                mockMvc.perform(post("/admin/transactions/sync_recharge_order")
                                .param("rechargeId", id)
                                .header("Authorization", "Bearer " + staffToken))
                        .andExpect(status().isForbidden());
            }

            assertThat(rechargeOrderMapper.selectById(id).getStatus()).isEqualTo(RechargeStatus.CLOSED);
            assertThat(userMapper.selectById(userId).getBalance()).isZero();
        }
    }

    /** 建立一筆儲值單,模擬付款並送一次回呼入帳 */
    private String paidRecharge(String userId, int amount) {
        String id = rechargeService.createRechargeOrder(userId, amount).getRechargeId();
        mockPayChannel.markPaid(id);
        rechargeService.handleNotify(id);
        return id;
    }

    private void insertTxn(String userId, TradeType type, int amount, LocalDateTime createdAt) {
        Transaction txn = new Transaction();
        txn.setTransactionId(UUID.randomUUID().toString());
        txn.setUserId(userId);
        txn.setOrderId(type == TradeType.DEBIT ? "ord-001" : null);
        txn.setAmount(amount);
        txn.setClosingBalance(0);
        txn.setType(type);
        txn.setCreatedBy(userId);
        txn.setCreatedAt(createdAt);
        transactionMapper.insert(txn);
    }

    private List<Transaction> rechargeTxns(String rechargeId) {
        return transactionMapper.selectList(
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getRechargeId, rechargeId));
    }
}
