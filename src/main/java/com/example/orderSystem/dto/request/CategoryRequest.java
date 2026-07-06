package com.example.orderSystem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CategoryRequest {

    @NotBlank(message = "必須輸入 name")
    private String name;

    @NotNull(message = "必須輸入 sortOrder")
    private Integer sortOrder;
}
