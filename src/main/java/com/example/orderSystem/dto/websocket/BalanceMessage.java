package com.example.orderSystem.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class BalanceMessage {

    private final String type = "BALANCE_UPDATED";
    private long availableBalance;
    private String reason;

    public BalanceMessage(long availableBalance, String reason) {
        this.availableBalance = availableBalance;
        this.reason = reason;
    }
}
