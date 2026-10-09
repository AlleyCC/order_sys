package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.response.RechargeOrderResponse;
import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.entity.Transaction;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.enums.TradeType;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.mapper.TransactionMapper;
import com.example.orderSystem.mapper.UserMapper;
import com.example.orderSystem.payment.MockPayChannel;
import com.example.orderSystem.payment.PayChannelException;
import com.example.orderSystem.support.AbstractIntegrationTest;
import com.example.orderSystem.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.example.orderSystem.support.TestFixtures.seedRechargeOrder;
import static com.example.orderSystem.support.TestFixtures.seedUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * 儲值的入帳與查單,打真的 MySQL。通道用模擬通道(spy 起來以便製造查單失敗),
 * 時間用可撥動的 MutableClock。
 *
 * 整合測試共用同一個 DB,別的測試可能留下 WAITING 的儲值單;
 * 這裡的斷言一律只看自己建立的單,不看批次處理的總筆數。
 */
class RechargeIntegrationTest extends AbstractIntegrationTest {

    @Autowired RechargeService rechargeService;
    @Autowired RechargeCreditService creditService;
    @Autowired RechargeOrderMapper rechargeOrderMapper;
    @Autowired TransactionMapper transactionMapper;
    @Autowired UserMapper userMapper;
    @Autowired MutableClock clock;

    @MockitoSpyBean MockPayChannel payChannel;
    @MockitoBean NotificationService notificationService;

    @AfterEach
    void tearDown() {
        clock.reset();
        reset(payChannel);
    }

    private String createRecharge(String userId, int amount) {
        return rechargeService.createRechargeOrder(userId, amount).getRechargeId();
    }

    private RechargeStatus statusOf(String rechargeId) {
        return rechargeOrderMapper.selectById(rechargeId).getStatus();
    }

    private long balanceOf(String userId) {
        return userMapper.selectById(userId).getBalance();
    }

    private List<Transaction> transactionsOf(String rechargeId) {
        return transactionMapper.selectList(
                new LambdaQueryWrapper<Transaction>().eq(Transaction::getRechargeId, rechargeId));
    }

    @Nested
    @DisplayName("入帳")
    class Credit {

        @Test
        @DisplayName("入帳:儲值單 SUCCESS + paid_at、餘額增加、寫一筆 RECHARGE 流水")
        void creditWritesEverything() {
            String userId = seedUser(userMapper, "rc", 300);
            String id = createRecharge(userId, 500);

            boolean credited = creditService.credit(id, List.of(RechargeStatus.WAITING), userId);

            assertThat(credited).isTrue();
            RechargeOrder order = rechargeOrderMapper.selectById(id);
            assertThat(order.getStatus()).isEqualTo(RechargeStatus.SUCCESS);
            assertThat(order.getPaidAt()).isNotNull();
            assertThat(balanceOf(userId)).isEqualTo(800L);

            List<Transaction> txns = transactionsOf(id);
            assertThat(txns).hasSize(1);
            Transaction txn = txns.get(0);
            assertThat(txn.getType()).isEqualTo(TradeType.RECHARGE);
            assertThat(txn.getUserId()).isEqualTo(userId);
            assertThat(txn.getAmount()).isEqualTo(500);
            assertThat(txn.getClosingBalance()).isEqualTo(800);
            assertThat(txn.getOrderId()).isNull();
            assertThat(txn.getCreatedBy()).isEqualTo(userId);
        }

        @Test
        @DisplayName("同一筆入帳兩次 → 第二次回傳 false,餘額只加一次、流水只有一筆")
        void creditTwiceOnlyOnce() {
            String userId = seedUser(userMapper, "rc", 0);
            String id = createRecharge(userId, 500);

            creditService.credit(id, List.of(RechargeStatus.WAITING), userId);
            boolean second = creditService.credit(id, List.of(RechargeStatus.WAITING), userId);

            assertThat(second).isFalse();
            assertThat(balanceOf(userId)).isEqualTo(500L);
            assertThat(transactionsOf(id)).hasSize(1);
        }

        @RepeatedTest(5)
        @DisplayName("兩個執行緒同時入帳同一筆 → 餘額只加一次、流水只有一筆")
        void concurrentCreditOnlyOnce() throws Exception {
            String userId = seedUser(userMapper, "rc", 0);
            String id = createRecharge(userId, 500);

            runConcurrently(
                    () -> creditService.credit(id, List.of(RechargeStatus.WAITING), userId),
                    () -> creditService.credit(id, List.of(RechargeStatus.WAITING), userId));

            assertThat(balanceOf(userId)).isEqualTo(500L);
            assertThat(transactionsOf(id)).hasSize(1);
        }

        @Test
        @DisplayName("入帳 commit 後推播入帳後的可用餘額")
        void pushesBalanceAfterCredit() {
            String userId = seedUser(userMapper, "rc", 100);
            String id = createRecharge(userId, 500);

            creditService.credit(id, List.of(RechargeStatus.WAITING), userId);

            verify(notificationService).sendBalanceUpdate(eq(userId), eq(600L), anyString());
        }

        @Test
        @DisplayName("寫流水失敗 → 儲值單狀態、餘額一起回滾")
        void rollsBackWhenTransactionInsertFails() {
            String userId = seedUser(userMapper, "rc", 100);
            String id = createRecharge(userId, 500);
            // 先佔掉這筆儲值單的流水,讓入帳的最後一步撞 UNIQUE
            Transaction squatter = new Transaction();
            squatter.setTransactionId(UUID.randomUUID().toString());
            squatter.setUserId(userId);
            squatter.setRechargeId(id);
            squatter.setAmount(1);
            squatter.setClosingBalance(0);
            squatter.setType(TradeType.RECHARGE);
            squatter.setCreatedBy(userId);
            transactionMapper.insert(squatter);

            assertThatThrownBy(() -> creditService.credit(id, List.of(RechargeStatus.WAITING), userId))
                    .isInstanceOf(DuplicateKeyException.class);

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
            assertThat(balanceOf(userId)).isEqualTo(100L);
        }

        @RepeatedTest(5)
        @DisplayName("過期關單與入帳同時處理同一筆 → 只會是 SUCCESS(加一次)或 CLOSED(沒加)其中之一")
        void closeAndCreditRace() throws Exception {
            String userId = seedUser(userMapper, "rc", 0);
            String id = createRecharge(userId, 500);

            runConcurrently(
                    () -> creditService.credit(id, List.of(RechargeStatus.WAITING), userId),
                    () -> rechargeOrderMapper.closeIfWaiting(id));

            if (statusOf(id) == RechargeStatus.SUCCESS) {
                assertThat(balanceOf(userId)).isEqualTo(500L);
                assertThat(transactionsOf(id)).hasSize(1);
            } else {
                assertThat(statusOf(id)).isEqualTo(RechargeStatus.CLOSED);
                assertThat(balanceOf(userId)).isZero();
                assertThat(transactionsOf(id)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("主動查單")
    class SyncRecentWaiting {

        @Test
        @DisplayName("回呼遺失:已付款的單在下一次主動查單時入帳")
        void picksUpDroppedCallback() {
            String userId = seedUser(userMapper, "rc", 0);
            String id = createRecharge(userId, 500);
            payChannel.markPaid(id);    // 付了,但沒有任何回呼

            rechargeService.syncRecentWaiting();

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.SUCCESS);
            assertThat(balanceOf(userId)).isEqualTo(500L);
        }

        @Test
        @DisplayName("建立超過 10 分鐘的單不在主動查單範圍(留給過期關單收尾)")
        void ignoresOrdersOutsideWindow() {
            String userId = seedUser(userMapper, "rc", 0);
            String old = createRecharge(userId, 500);
            payChannel.markPaid(old);
            clock.advance(Duration.ofMinutes(11));

            rechargeService.syncRecentWaiting();

            assertThat(statusOf(old)).isEqualTo(RechargeStatus.WAITING);
        }

        @Test
        @DisplayName("通道回覆尚未付款或查無此單 → 維持 WAITING,之後付款仍可入帳")
        void unpaidOrUnknownStaysWaiting() {
            String userId = seedUser(userMapper, "rc", 0);
            String unpaid = createRecharge(userId, 500);
            String unknownToChannel = seedRechargeOrder(rechargeOrderMapper, userId, 300, RechargeStatus.WAITING);

            rechargeService.syncRecentWaiting();

            assertThat(statusOf(unpaid)).isEqualTo(RechargeStatus.WAITING);
            assertThat(statusOf(unknownToChannel)).isEqualTo(RechargeStatus.WAITING);

            payChannel.markPaid(unpaid);
            rechargeService.syncRecentWaiting();
            assertThat(statusOf(unpaid)).isEqualTo(RechargeStatus.SUCCESS);
        }

        @Test
        @DisplayName("其中一筆查單失敗 → 其他筆照常入帳,失敗的那筆維持 WAITING")
        void oneFailureDoesNotStopOthers() {
            String userId = seedUser(userMapper, "rc", 0);
            String broken = createRecharge(userId, 100);
            String fine = createRecharge(userId, 500);
            payChannel.markPaid(broken);
            payChannel.markPaid(fine);
            doThrow(new PayChannelException("通道逾時", null)).when(payChannel).queryPayment(broken);

            rechargeService.syncRecentWaiting();

            assertThat(statusOf(broken)).isEqualTo(RechargeStatus.WAITING);
            assertThat(statusOf(fine)).isEqualTo(RechargeStatus.SUCCESS);
            assertThat(balanceOf(userId)).isEqualTo(500L);
        }
    }

    @Nested
    @DisplayName("過期關單")
    class ExpireOverdue {

        @Test
        @DisplayName("已過期:付了的入帳、沒付的關單、查單失敗的留著下次再試")
        void lastCheckBeforeClosing() {
            String userId = seedUser(userMapper, "rc", 0);
            String paid = createRecharge(userId, 500);
            String unpaid = createRecharge(userId, 200);
            String broken = createRecharge(userId, 100);
            payChannel.markPaid(paid);    // 回呼遺失,主動查單也沒趕上
            clock.advance(Duration.ofMinutes(16));
            doThrow(new PayChannelException("通道逾時", null)).when(payChannel).queryPayment(broken);

            rechargeService.expireOverdue();

            assertThat(statusOf(paid)).isEqualTo(RechargeStatus.SUCCESS);
            assertThat(statusOf(unpaid)).isEqualTo(RechargeStatus.CLOSED);
            assertThat(statusOf(broken)).isEqualTo(RechargeStatus.WAITING);
            assertThat(balanceOf(userId)).isEqualTo(500L);
        }

        @Test
        @DisplayName("還沒過期的 WAITING 單不受影響")
        void notYetExpiredUntouched() {
            String userId = seedUser(userMapper, "rc", 0);
            String id = createRecharge(userId, 500);
            clock.advance(Duration.ofMinutes(14));

            rechargeService.expireOverdue();

            assertThat(statusOf(id)).isEqualTo(RechargeStatus.WAITING);
        }
    }

    @Test
    @DisplayName("建立儲值單:本地 WAITING,通道付款期限早於本地過期時間")
    void createRegistersPaymentWithEarlierDeadline() {
        String userId = seedUser(userMapper, "rc", 0);

        RechargeOrderResponse resp = rechargeService.createRechargeOrder(userId, 500);

        assertThat(resp.getStatus()).isEqualTo(RechargeStatus.WAITING);
        assertThat(resp.getPayInfo()).isNotBlank();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        // 通道已關閉付款入口,本地還沒過期
        assertThatThrownBy(() -> payChannel.markPaid(resp.getRechargeId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(resp.getExpireAt()).isAfter(LocalDateTime.now(clock));
    }

    private static void runConcurrently(Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<Void> fa = CompletableFuture.runAsync(() -> { await(start); a.run(); }, pool);
            CompletableFuture<Void> fb = CompletableFuture.runAsync(() -> { await(start); b.run(); }, pool);
            start.countDown();
            CompletableFuture.allOf(fa, fb).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
