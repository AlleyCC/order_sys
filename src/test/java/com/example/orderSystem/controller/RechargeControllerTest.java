package com.example.orderSystem.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.payment.MockPayChannel;
import com.example.orderSystem.payment.PayChannelException;
import com.example.orderSystem.service.NotificationService;
import com.example.orderSystem.service.RechargeService;
import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.support.MutableClock;
import com.example.orderSystem.util.JwtUtils;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;

import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 儲值的 HTTP 端點。覆寫的 bean 與 RechargeIntegrationTest 相同,兩者共用同一個 Spring context。
 */
class RechargeControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtUtils jwtUtils;
    @Autowired UserMapper userMapper;
    @Autowired RechargeOrderMapper rechargeOrderMapper;
    @Autowired TransactionMapper transactionMapper;
    @Autowired RechargeService rechargeService;
    @Autowired MutableClock clock;

    @MockitoSpyBean MockPayChannel payChannel;
    @MockitoBean NotificationService notificationService;

    @AfterEach
    void tearDown() {
        clock.reset();
        reset(payChannel);
    }

    private String bearer(String userId) {
        return "Bearer " + jwtUtils.generateAccessToken(userId);
    }

    private String createRecharge(String userId, String amountJson) throws Exception {
        String body = mockMvc.perform(post("/user/create_recharge_order")
                        .header("Authorization", bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amountJson + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.rechargeId");
    }

    private RechargeStatus statusOf(String rechargeId) {
        return rechargeOrderMapper.selectById(rechargeId).getStatus();
    }

    private long balanceOf(String userId) {
        return userMapper.selectById(userId).getBalance();
    }

    private long transactionCount(String rechargeId) {
        return transactionMapper.selectCount(
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getRechargeId, rechargeId));
    }

    @Nested
    @DisplayName("POST /user/create_recharge_order")
    class Create {

        @Test
        @DisplayName("金額 500 → 200,回覆單號、WAITING、過期時間、付款資訊;餘額與流水不變")
        void createsWaitingOrder() throws Exception {
            String userId = seedUser(userMapper, "rcc", 100);

            String body = mockMvc.perform(post("/user/create_recharge_order")
                            .header("Authorization", bearer(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 500}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.amount").value(500))
                    .andExpect(jsonPath("$.status").value("WAITING"))
                    .andExpect(jsonPath("$.expireAt").exists())
                    .andExpect(jsonPath("$.payInfo").exists())
                    .andReturn().getResponse().getContentAsString();

            String id = JsonPath.read(body, "$.rechargeId");
            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
            assertThat(rechargeOrderMapper.selectById(id).getUserId()).isEqualTo(userId);
            assertThat(balanceOf(userId)).isEqualTo(100L);
            assertThat(transactionCount(id)).isZero();
        }

        @ParameterizedTest(name = "amount = {0} → 400")
        @ValueSource(strings = {"0", "-1", "50001", "1.5", "null"})
        @DisplayName("金額不合法 → 400,不建立儲值單")
        void rejectsInvalidAmount(String amount) throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);

            mockMvc.perform(post("/user/create_recharge_order")
                            .header("Authorization", bearer(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": " + amount + "}"))
                    .andExpect(status().isBadRequest());

            assertThat(rechargeOrderMapper.selectCount(new LambdaQueryWrapper<RechargeOrder>()
                    .eq(RechargeOrder::getUserId, userId))).isZero();
        }

        @Test
        @DisplayName("金額 50000 剛好是上限 → 200")
        void acceptsUpperLimit() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);

            createRecharge(userId, "50000");
        }

        @Test
        @DisplayName("請求裡帶別人的 userId 也沒用:儲值對象一律是呼叫者本人")
        void ignoresUserIdInBody() throws Exception {
            String me = seedUser(userMapper, "rcc", 0);
            String other = seedUser(userMapper, "rcc", 0);

            String body = mockMvc.perform(post("/user/create_recharge_order")
                            .header("Authorization", bearer(me))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 500, \"userId\": \"" + other + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String id = JsonPath.read(body, "$.rechargeId");
            assertThat(rechargeOrderMapper.selectById(id).getUserId()).isEqualTo(me);
        }

        @Test
        @DisplayName("通道建立付款失敗 → 502,不留下 WAITING 的單")
        void channelFailureLeavesNoWaitingOrder() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            doThrow(new PayChannelException("通道掛了", null))
                    .when(payChannel).createPayment(anyString(), anyInt(), any(LocalDateTime.class));

            mockMvc.perform(post("/user/create_recharge_order")
                            .header("Authorization", bearer(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\": 500}"))
                    .andExpect(status().isBadGateway());

            assertThat(rechargeOrderMapper.selectCount(new LambdaQueryWrapper<RechargeOrder>()
                    .eq(RechargeOrder::getUserId, userId)
                    .eq(RechargeOrder::getStatus, RechargeStatus.WAITING))).isZero();
        }
    }

    @Nested
    @DisplayName("GET /user/get_recharge_order")
    class GetOwn {

        @Test
        @DisplayName("查自己的單 → 200")
        void readsOwnOrder() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");

            mockMvc.perform(get("/user/get_recharge_order")
                            .param("rechargeId", id)
                            .header("Authorization", bearer(userId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.rechargeId").value(id))
                    .andExpect(jsonPath("$.status").value("WAITING"))
                    .andExpect(jsonPath("$.paidAt").doesNotExist())
                    .andExpect(jsonPath("$.payInfo").doesNotExist());
        }

        @Test
        @DisplayName("查別人的單 → 404,和查無此單一樣")
        void othersOrderIs404() throws Exception {
            String owner = seedUser(userMapper, "rcc", 0);
            String stranger = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(owner, "500");

            mockMvc.perform(get("/user/get_recharge_order")
                            .param("rechargeId", id)
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/user/get_recharge_order")
                            .param("rechargeId", "no-such")
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("回呼與模擬付款")
    class Callbacks {

        private void pay(String rechargeId, String callback) throws Exception {
            mockMvc.perform(post("/mock-channel/pay")
                            .param("rechargeId", rechargeId)
                            .param("callback", callback))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("未登入也能呼叫回呼端點與模擬付款端點(白名單)")
        void endpointsAreWhitelisted() throws Exception {
            mockMvc.perform(post("/pay/notify/mock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rechargeId\": \"no-such\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/mock-channel/pay")
                            .param("rechargeId", "no-such")
                            .param("callback", "ONCE"))
                    .andExpect(status().isBadRequest());     // 不是 401:有進到端點,只是通道查無此付款
        }

        @Test
        @DisplayName("付款 + 回呼一次 → SUCCESS,餘額增加")
        void callbackOnce() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");

            pay(id, "ONCE");

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.SUCCESS);
            assertThat(balanceOf(userId)).isEqualTo(500L);
        }

        @Test
        @DisplayName("付款 + 回呼兩次 → 餘額只加一次、流水一筆")
        void callbackTwice() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");

            pay(id, "TWICE");

            assertThat(balanceOf(userId)).isEqualTo(500L);
            assertThat(transactionCount(id)).isEqualTo(1);
        }

        @Test
        @DisplayName("付款但回呼遺失 → 仍是 WAITING;下一次主動查單補上")
        void callbackDropped() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");

            pay(id, "DROP");
            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
            assertThat(balanceOf(userId)).isZero();

            rechargeService.syncRecentWaiting();

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.SUCCESS);
            assertThat(balanceOf(userId)).isEqualTo(500L);
        }

        @Test
        @DisplayName("偽造回呼:沒付款就送回呼 → 回覆成功,但不入帳")
        void forgedCallbackDoesNotCredit() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");

            mockMvc.perform(post("/pay/notify/mock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rechargeId\": \"" + id + "\", \"status\": \"PAID\", \"amount\": 500}"))
                    .andExpect(status().isOk());

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
            assertThat(balanceOf(userId)).isZero();
        }

        @Test
        @DisplayName("回呼時查通道失敗 → 5xx(讓通道重送),狀態不變")
        void channelQueryFailureReturns5xx() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");
            payChannel.markPaid(id);
            doThrow(new PayChannelException("通道逾時", null)).when(payChannel).queryPayment(id);

            mockMvc.perform(post("/pay/notify/mock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rechargeId\": \"" + id + "\"}"))
                    .andExpect(status().is5xxServerError());

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
        }

        @Test
        @DisplayName("通道付款期限已過 → 模擬付款失敗 400")
        void payAfterChannelDeadlineFails() throws Exception {
            String userId = seedUser(userMapper, "rcc", 0);
            String id = createRecharge(userId, "500");
            clock.advance(Duration.ofMinutes(11));

            mockMvc.perform(post("/mock-channel/pay")
                            .param("rechargeId", id)
                            .param("callback", "ONCE"))
                    .andExpect(status().isBadRequest());

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
        }
    }
}
