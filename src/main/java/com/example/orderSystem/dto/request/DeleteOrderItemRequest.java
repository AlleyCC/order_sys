package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeleteOrderItemRequest {

    @NotBlank(message = "必須輸入 orderId")
    private String orderId;

    @NotBlank(message = "必須輸入 itemId")
    private String itemId; // 數字 ID 或 "all"
}
