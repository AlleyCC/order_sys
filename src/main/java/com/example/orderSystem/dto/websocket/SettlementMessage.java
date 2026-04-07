package com.example.orderSystem.dto.websocket;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SettlementMessage {

    @Builder.Default
    private final String type = "SETTLEMENT";
    private String orderId;
    private String orderName;
    private String result;
    private Integer amount;
    private Long balance;
    private String detail;
}
