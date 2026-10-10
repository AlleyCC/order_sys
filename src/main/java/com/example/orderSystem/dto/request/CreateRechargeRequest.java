package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateRechargeRequest {

    /**
     * 用 BigDecimal 接,才擋得住 1.5 這種小數:宣告成 Integer 的話,
     * Jackson 預設會把 1.5 默默截成 1,使用者以為儲值 1.5 元、實際建立 1 元的單。
     */
    @NotNull(message = "必須輸入 amount")
    @Digits(integer = 5, fraction = 0, message = "amount 必須是整數")
    @DecimalMin(value = "1", message = "amount 必須介於 1 到 50000")
    @DecimalMax(value = "50000", message = "amount 必須介於 1 到 50000")
    private BigDecimal amount;
}
