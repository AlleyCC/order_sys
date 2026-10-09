package com.example.orderSystem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orderSystem.dto.response.RechargeOrderResponse;
import com.example.orderSystem.entity.RechargeOrder;
import com.example.orderSystem.enums.RechargeStatus;
import com.example.orderSystem.exception.ResourceNotFoundException;
import com.example.orderSystem.mapper.RechargeOrderMapper;
import com.example.orderSystem.payment.ChannelPayment;
import com.example.orderSystem.payment.ChannelPaymentStatus;
import com.example.orderSystem.payment.PayChannel;
import com.example.orderSystem.payment.PayChannelException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 儲值單的生命週期。這裡的方法都只負責「問出付款結果」,入帳一律交給 {@link RechargeCreditService}。
 *
 * 向通道查單是外部 I/O,不放在資料庫交易內:通道慢的時候,不該拖住 DB 連線和列鎖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeService {

    /** 本地儲值單的過期時間 */
    static final Duration LOCAL_EXPIRY = Duration.ofMinutes(15);
    /** 通道付款期限。必須早於本地過期:關單時通道那邊已經不可能再收款 */
    static final Duration CHANNEL_EXPIRY = Duration.ofMinutes(10);
    /** 主動查單只看這段時間內建立的單;更早的單通道已關閉付款入口,留給過期關單收尾 */
    static final Duration SYNC_WINDOW = Duration.ofMinutes(10);

    private static final List<RechargeStatus> AUTO = List.of(RechargeStatus.WAITING);
    private static final List<RechargeStatus> MANUAL = List.of(RechargeStatus.WAITING, RechargeStatus.CLOSED);

    private final RechargeOrderMapper rechargeOrderMapper;
    private final PayChannel payChannel;
    private final RechargeCreditService creditService;
    private final Clock clock;

    /**
     * 先寫本地、再到通道建立付款。反過來的話,通道建好了、本地卻寫失敗,
     * 使用者付了錢會找不到可以入帳的單;這個順序最壞只會多一筆 CLOSED。
     */
    public RechargeOrderResponse createRechargeOrder(String userId, int amount) {
        LocalDateTime now = LocalDateTime.now(clock);
        RechargeOrder order = new RechargeOrder();
        order.setRechargeId(UUID.randomUUID().toString());
        order.setUserId(userId);
        order.setAmount(amount);
        order.setStatus(RechargeStatus.WAITING);
        order.setCreatedAt(now);
        order.setExpireAt(now.plus(LOCAL_EXPIRY));
        rechargeOrderMapper.insert(order);

        String payInfo;
        try {
            payInfo = payChannel.createPayment(order.getRechargeId(), amount, now.plus(CHANNEL_EXPIRY));
        } catch (RuntimeException e) {
            rechargeOrderMapper.closeIfWaiting(order.getRechargeId());
            throw new PayChannelException("付款通道建立付款失敗", e);
        }

        RechargeOrderResponse resp = RechargeOrderResponse.from(order);
        resp.setPayInfo(payInfo);
        return resp;
    }

    /** 查詢自己的儲值單;別人的單和不存在的單回一樣的 404,不洩漏單號是否存在 */
    public RechargeOrderResponse getOwnRechargeOrder(String userId, String rechargeId) {
        RechargeOrder order = rechargeOrderMapper.selectById(rechargeId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("儲值單不存在: " + rechargeId);
        }
        return RechargeOrderResponse.from(order);
    }

    /**
     * 回呼:內容不採信,只拿單號回頭查通道。本地查無此單也正常結束,讓通道停止重送;
     * 查通道失敗則把例外往外丟,讓回呼回覆失敗、通道稍後重送。
     */
    public void handleNotify(String rechargeId) {
        RechargeOrder order = rechargeOrderMapper.selectById(rechargeId);
        if (order == null) {
            log.warn("Notify for unknown recharge order {}", rechargeId);
            return;
        }
        syncWithChannel(order, AUTO, order.getUserId());
    }

    /** 會計人工對單。只有這條路徑能讓 CLOSED 的單入帳 —— 要不要相信通道的事後改口,交給人判斷 */
    public RechargeOrderResponse manualSync(String rechargeId, String operator) {
        RechargeOrder order = rechargeOrderMapper.selectById(rechargeId);
        if (order == null) {
            throw new ResourceNotFoundException("儲值單不存在: " + rechargeId);
        }
        if (order.getStatus() != RechargeStatus.SUCCESS) {
            syncWithChannel(order, MANUAL, operator);
        }
        return RechargeOrderResponse.from(rechargeOrderMapper.selectById(rechargeId));
    }

    /**
     * 主動查單,補上遺失的回呼。查到「沒付」「查無此單」都不動:
     * 使用者可能下一秒才付款,判定沒付只交給過期關單。
     *
     * @return 本輪入帳的筆數
     */
    public int syncRecentWaiting() {
        LocalDateTime since = LocalDateTime.now(clock).minus(SYNC_WINDOW);
        List<RechargeOrder> orders = rechargeOrderMapper.selectList(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getStatus, RechargeStatus.WAITING)
                .ge(RechargeOrder::getCreatedAt, since));

        int credited = 0;
        for (RechargeOrder order : orders) {
            try {
                credited += syncWithChannel(order, AUTO, order.getUserId()) ? 1 : 0;
            } catch (Exception e) {
                // 單筆失敗不影響其他筆;這筆維持 WAITING,下一輪再查
                log.error("Failed to sync recharge order {}", order.getRechargeId(), e);
            }
        }
        return credited;
    }

    /**
     * 過期關單。關單前最後查一次通道:付了就入帳、不關單。
     * 查單失敗不關單 —— 查不到結果不等於沒付款,下一輪再試。
     *
     * @return 本輪關閉的筆數
     */
    public int expireOverdue() {
        List<RechargeOrder> orders = rechargeOrderMapper.selectList(new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getStatus, RechargeStatus.WAITING)
                .lt(RechargeOrder::getExpireAt, LocalDateTime.now(clock)));

        int closed = 0;
        for (RechargeOrder order : orders) {
            try {
                ChannelPayment payment = payChannel.queryPayment(order.getRechargeId());
                if (payment.status() == ChannelPaymentStatus.PAID) {
                    creditIfAmountMatches(order, payment, AUTO, order.getUserId());
                    continue;
                }
                // 條件式關單:和回呼入帳同時發生時,只有一邊會成功
                closed += rechargeOrderMapper.closeIfWaiting(order.getRechargeId());
            } catch (Exception e) {
                log.error("Failed to expire recharge order {}", order.getRechargeId(), e);
            }
        }
        return closed;
    }

    /** @return 這次是否入帳 */
    private boolean syncWithChannel(RechargeOrder order, Collection<RechargeStatus> allowedFrom, String operator) {
        ChannelPayment payment = payChannel.queryPayment(order.getRechargeId());
        if (payment.status() != ChannelPaymentStatus.PAID) {
            return false;
        }
        return creditIfAmountMatches(order, payment, allowedFrom, operator);
    }

    private boolean creditIfAmountMatches(RechargeOrder order, ChannelPayment payment,
                                          Collection<RechargeStatus> allowedFrom, String operator) {
        if (payment.amount() != order.getAmount()) {
            // 付了但金額對不上,不能自動處理;留給人工調整
            log.error("Amount mismatch for recharge order {}: local={}, channel={}",
                    order.getRechargeId(), order.getAmount(), payment.amount());
            return false;
        }
        return creditService.credit(order.getRechargeId(), allowedFrom, operator);
    }
}
