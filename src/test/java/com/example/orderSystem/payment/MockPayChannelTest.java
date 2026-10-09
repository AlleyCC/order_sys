package com.example.orderSystem.payment;

import com.example.orderSystem.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPayChannelTest {

    private final MutableClock clock = new MutableClock();
    private final MockPayChannel channel = new MockPayChannel(clock);

    private LocalDateTime tenMinutesLater() {
        return LocalDateTime.now(clock).plusMinutes(10);
    }

    @Test
    @DisplayName("建立付款後查詢為 WAITING,金額與建立時相同")
    void createdPaymentIsWaiting() {
        channel.createPayment("r-1", 500, tenMinutesLater());

        ChannelPayment payment = channel.queryPayment("r-1");

        assertThat(payment.status()).isEqualTo(ChannelPaymentStatus.WAITING);
        assertThat(payment.amount()).isEqualTo(500);
    }

    @Test
    @DisplayName("未建立的付款查詢為 NOT_FOUND")
    void unknownPaymentIsNotFound() {
        assertThat(channel.queryPayment("no-such").status()).isEqualTo(ChannelPaymentStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("標記付款後查詢為 PAID;重複標記不影響結果")
    void markPaid() {
        channel.createPayment("r-2", 300, tenMinutesLater());

        channel.markPaid("r-2");
        channel.markPaid("r-2");

        ChannelPayment payment = channel.queryPayment("r-2");
        assertThat(payment.status()).isEqualTo(ChannelPaymentStatus.PAID);
        assertThat(payment.amount()).isEqualTo(300);
    }

    @Test
    @DisplayName("超過付款期限:查詢為 CLOSED,且無法再付款")
    void expiredPaymentIsClosed() {
        channel.createPayment("r-3", 500, tenMinutesLater());

        clock.advance(Duration.ofMinutes(11));

        assertThat(channel.queryPayment("r-3").status()).isEqualTo(ChannelPaymentStatus.CLOSED);
        assertThatThrownBy(() -> channel.markPaid("r-3")).isInstanceOf(IllegalStateException.class);
        assertThat(channel.queryPayment("r-3").status()).isEqualTo(ChannelPaymentStatus.CLOSED);
    }

    @Test
    @DisplayName("期限前已付款,過了期限仍是 PAID(付款期限只擋還沒付的)")
    void paidBeforeDeadlineStaysPaid() {
        channel.createPayment("r-4", 500, tenMinutesLater());
        channel.markPaid("r-4");

        clock.advance(Duration.ofMinutes(11));

        assertThat(channel.queryPayment("r-4").status()).isEqualTo(ChannelPaymentStatus.PAID);
    }

    @Test
    @DisplayName("對不存在的付款模擬付款 → 失敗")
    void markUnknownPaymentFails() {
        assertThatThrownBy(() -> channel.markPaid("no-such")).isInstanceOf(IllegalStateException.class);
    }
}
