package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 全量替換某使用者擁有的角色(空清單 = 拔掉所有角色) */
@Data
public class UpdateUserRolesRequest {

    @NotBlank(message = "必須輸入 userId")
    private String userId;

    @NotNull(message = "必須輸入 roleNames")
    private List<String> roleNames;
}
