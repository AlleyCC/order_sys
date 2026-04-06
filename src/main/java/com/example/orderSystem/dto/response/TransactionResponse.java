package com.example.orderSystem.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TransactionResponse {

    private String transactionId;
    private Integer amount;
    private LocalDateTime createdAt;
}
