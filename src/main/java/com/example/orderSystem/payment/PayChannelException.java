package com.example.orderSystem.payment;

/** 付款通道呼叫失敗。付款結果未知,呼叫端不能把它當成「沒付款」。 */
public class PayChannelException extends RuntimeException {

    public PayChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
