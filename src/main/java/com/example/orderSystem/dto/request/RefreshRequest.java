package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshRequest {

    @NotBlank(message = "必須輸入 refreshToken")
    private String refreshToken;
}
