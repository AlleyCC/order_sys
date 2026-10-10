package com.example.orderSystem.payment;

/** 向付款通道查單的結果。NOT_FOUND 時 amount 為 0。 */
public record ChannelPayment(String rechargeId, ChannelPaymentStatus status, int amount) {

    public static ChannelPayment notFound(String rechargeId) {
        return new ChannelPayment(rechargeId, ChannelPaymentStatus.NOT_FOUND, 0);
    }
}
