package com.example.orderSystem.service;

import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.payment.ChannelPayment;
import com.example.orderSystem.payment.ChannelPaymentStatus;
import com.example.orderSystem.payment.PayChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「向通道查到什麼 → 要不要入帳」的判斷。入帳本身(條件式寫入、加餘額、寫流水)
 * 由 RechargeCreditService 負責,在整合測試裡驗證。
 */
@ExtendWith(MockitoExtension.class)
class RechargeServiceTest {

    private static final String RECHARGE_ID = "r-1";
    private static final String OWNER = "alice";

    @Mock RechargeOrderMapper rechargeOrderMapper;
    @Mock PayChannel payChannel;
    @Mock RechargeCreditService creditService;
    @Spy Clock clock = Clock.systemDefaultZone();

    @InjectMocks RechargeService rechargeService;

    @BeforeEach
    void setUp() {
        // 個別測試會改成 CLOSED / SUCCESS 或查無此單,所以預設值用 lenient
        lenient().when(rechargeOrderMapper.selectById(RECHARGE_ID)).thenReturn(order(RechargeStatus.WAITING));
    }

    @Test
    @DisplayName("回呼:通道查到 PAID 且金額相符 → 以儲值單擁有者為操作者,從 WAITING 入帳")
    void notifyCreditsWhenPaid() {
        when(payChannel.queryPayment(RECHARGE_ID)).thenReturn(payment(ChannelPaymentStatus.PAID, 500));

        rechargeService.handleNotify(RECHARGE_ID);

        verify(creditService).credit(RECHARGE_ID, List.of(RechargeStatus.WAITING), OWNER);
    }

    @Test
    @DisplayName("回呼:通道查到 PAID 但金額不符 → 不入帳")
    void notifyIgnoresAmountMismatch() {
        when(payChannel.queryPayment(RECHARGE_ID)).thenReturn(payment(ChannelPaymentStatus.PAID, 499));

        rechargeService.handleNotify(RECHARGE_ID);

        verify(creditService, never()).credit(anyString(), anyCollection(), anyString());
    }

    @ParameterizedTest(name = "通道回覆 {0} → 不入帳")
    @EnumSource(value = ChannelPaymentStatus.class, names = {"WAITING", "CLOSED", "NOT_FOUND"})
    @DisplayName("回呼:通道沒有回覆 PAID → 不入帳(回呼內容不採信)")
    void notifyIgnoresUnpaid(ChannelPaymentStatus status) {
        when(payChannel.queryPayment(RECHARGE_ID)).thenReturn(payment(status, 500));

        rechargeService.handleNotify(RECHARGE_ID);

        verify(creditService, never()).credit(anyString(), anyCollection(), anyString());
    }

    @Test
    @DisplayName("回呼:本地查無此單 → 不查通道、不拋例外(讓通道停止重送)")
    void notifyUnknownOrderIsIgnored() {
        when(rechargeOrderMapper.selectById("no-such")).thenReturn(null);

        rechargeService.handleNotify("no-such");

        verifyNoInteractions(payChannel, creditService);
    }

    @Test
    @DisplayName("人工對單:CLOSED 的單查到 PAID → 允許從 WAITING/CLOSED 入帳,操作者為會計")
    void manualSyncCanCreditClosedOrder() {
        when(rechargeOrderMapper.selectById(RECHARGE_ID)).thenReturn(order(RechargeStatus.CLOSED));
        when(payChannel.queryPayment(RECHARGE_ID)).thenReturn(payment(ChannelPaymentStatus.PAID, 500));

        rechargeService.manualSync(RECHARGE_ID, "accountant01");

        verify(creditService).credit(RECHARGE_ID,
                List.of(RechargeStatus.WAITING, RechargeStatus.CLOSED), "accountant01");
    }

    @Test
    @DisplayName("人工對單:已入帳的單 → 不查通道")
    void manualSyncSkipsPaidOrder() {
        when(rechargeOrderMapper.selectById(RECHARGE_ID)).thenReturn(order(RechargeStatus.SUCCESS));

        rechargeService.manualSync(RECHARGE_ID, "accountant01");

        verifyNoInteractions(payChannel, creditService);
    }

    private static RechargeOrder order(RechargeStatus status) {
        RechargeOrder order = new RechargeOrder();
        order.setRechargeId(RECHARGE_ID);
        order.setUserId(OWNER);
        order.setAmount(500);
        order.setStatus(status);
        return order;
    }

    private static ChannelPayment payment(ChannelPaymentStatus status, int amount) {
        return new ChannelPayment(RECHARGE_ID, status, amount);
    }
}
