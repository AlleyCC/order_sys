package com.example.orderSystem.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模擬付款通道,只用於開發與測試。付款資料存在記憶體:重啟即消失,也不跨機器共用。
 *
 * 付款期限過了還沒付的單,查詢回覆 CLOSED 且不能再付 —— 對應真實通道「先關付款入口」的行為,
 * 本地儲值單的過期時間比它晚,過期關單時通道這邊已經不可能再收款。
 *
 * 這裡不負責送回呼:誰要送、送幾次由 MockChannelController 決定,
 * 這個類別因此不需要依賴儲值 service,避免循環依賴。
 */
@Component
@ConditionalOnProperty(name = "app.pay.channel", havingValue = "mock", matchIfMissing = true)
public class MockPayChannel implements PayChannel {

    private final Clock clock;
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    public MockPayChannel(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String createPayment(String rechargeId, int amount, LocalDateTime payExpireAt) {
        payments.put(rechargeId, new Payment(amount, payExpireAt, false));
        return "mock://pay/" + rechargeId;
    }

    @Override
    public ChannelPayment queryPayment(String rechargeId) {
        Payment payment = payments.get(rechargeId);
        if (payment == null) {
            return ChannelPayment.notFound(rechargeId);
        }
        return new ChannelPayment(rechargeId, statusOf(payment), payment.amount());
    }

    /** 模擬使用者在通道完成付款。已付款再付一次沒有影響;不存在或已過期則失敗。 */
    public void markPaid(String rechargeId) {
        Payment updated = payments.computeIfPresent(rechargeId, (id, payment) -> {
            if (statusOf(payment) == ChannelPaymentStatus.CLOSED) {
                throw new IllegalStateException("付款期限已過: " + id);
            }
            return payment.markPaid();
        });
        if (updated == null) {
            throw new IllegalStateException("通道查無此付款: " + rechargeId);
        }
    }

    private ChannelPaymentStatus statusOf(Payment payment) {
        if (payment.paid()) {
            return ChannelPaymentStatus.PAID;
        }
        return LocalDateTime.now(clock).isBefore(payment.expireAt())
                ? ChannelPaymentStatus.WAITING
                : ChannelPaymentStatus.CLOSED;
    }

    private record Payment(int amount, LocalDateTime expireAt, boolean paid) {

        Payment markPaid() {
            return new Payment(amount, expireAt, true);
        }
    }
}
