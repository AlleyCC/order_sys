package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改分類的請求(PATCH 部分更新)。
 * name / sortOrder 可省略(= 不改該欄);version 是樂觀鎖 token,不是給人改的欄位。
 */
@Data
public class CategoryUpdateRequest {

    @NotNull(message = "必須輸入 categoryId")
    private Integer categoryId;

    @NotNull(message = "必須輸入 version")
    private Integer version;

    // 部分更新:可省略(null=不改)。但一旦有送,就不准是空白字串。
    // @Pattern 對 null 放行、對非 null 才驗 → 剛好符合「可省略但不可空白」的語意。
    @Pattern(regexp = ".*\\S.*", message = "name 不可為空白")
    @Size(max = 50, message = "name 長度上限 50")
    private String name;

    // 可省略(null=不改)。有送就更新為該值。
    private Integer sortOrder;
}
