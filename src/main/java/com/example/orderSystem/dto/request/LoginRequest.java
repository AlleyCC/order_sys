package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "必須輸入 userId")
    @Size(max = 20, message = "userId 長度不可超過 20")
    @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "userId 只能包含英數字與 . _ -")
    private String userId;
}
