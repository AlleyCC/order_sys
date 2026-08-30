package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 全量替換某角色被授權的資源清單(空清單 = 清空該角色所有授權) */
@Data
public class UpdateRoleResourcesRequest {

    @NotBlank(message = "必須輸入 roleName")
    private String roleName;

    @NotNull(message = "必須輸入 resourceIds")
    private List<Long> resourceIds;
}
