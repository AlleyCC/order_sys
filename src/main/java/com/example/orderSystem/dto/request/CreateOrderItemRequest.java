package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateOrderItemRequest {

    @NotBlank(message = "必須輸入 orderId")
    private String orderId;

    @NotNull(message = "必須輸入 menuId")
    private Integer menuId;

    @NotNull(message = "必須輸入 quantity")
    @Min(value = 1, message = "quantity 必須大於 0")
    private Integer quantity;
}
