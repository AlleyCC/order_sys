package com.example.orderSystem.dto.websocket;

import lombok.Data;

@Data
public class BalanceMessage {

    private final String type = "BALANCE_CHANGED";
    private final String reason;
}
