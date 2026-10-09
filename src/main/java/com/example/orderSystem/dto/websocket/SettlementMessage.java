package com.example.orderSystem.dto.websocket;

import lombok.Builder;
import lombok.Data;

/**
 * 結算結果推播。內容和站內通知同一則,notificationId 讓前端與「查詢未讀」的結果去重。
 */
@Data
@Builder
public class SettlementMessage {

    @Builder.Default
    private final String type = "SETTLEMENT";
    private String notificationId;
    private String orderId;
    private String result;
    private String detail;
}
