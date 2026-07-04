package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Category;

/**
 * 分類的對外回應契約。只曝露前端需要的欄位,
 * 刻意「不含」isDeleted / createdBy / 時間戳等內部欄位。
 */
public record CategoryResponse(Integer categoryId, String name, Integer sortOrder) {

    public static CategoryResponse from(Category c) {
        return new CategoryResponse(c.getCategoryId(), c.getName(), c.getSortOrder());
    }
}
