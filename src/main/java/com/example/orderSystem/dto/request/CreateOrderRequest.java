package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateOrderRequest {

    @NotBlank(message = "必須輸入 storeId")
    private String storeId;

    @NotBlank(message = "必須輸入 orderName")
    private String orderName;

    @NotBlank(message = "必須輸入 deadline")
    private String deadline;
}
