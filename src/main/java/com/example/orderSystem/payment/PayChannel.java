package com.example.orderSystem.payment;

import java.time.LocalDateTime;

/**
 * 外部付款通道。PayPool 只透過這兩個動作和通道互動:
 * 建立付款,以及查詢付款結果。回呼不在介面裡 —— 回呼的內容一律不採信,
 * 收到回呼後一樣是呼叫 queryPayment 查單。
 *
 * 通道出錯(連線失敗、逾時、回應異常)時拋出 {@link PayChannelException}。
 */
public interface PayChannel {

    /**
     * 建立付款,回傳給使用者的付款資訊(例如付款頁網址)。
     *
     * @param payExpireAt 通道付款期限,必須早於本地儲值單的過期時間
     */
    String createPayment(String rechargeId, int amount, LocalDateTime payExpireAt);

    ChannelPayment queryPayment(String rechargeId);
}
