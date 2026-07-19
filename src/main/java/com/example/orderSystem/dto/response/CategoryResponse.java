package com.example.orderSystem.dto.response;

import com.example.orderSystem.entity.Category;

/**
 * 分類的對外回應契約。只曝露前端需要的欄位,
 * 刻意「不含」isDeleted / createdBy / 時間戳等內部欄位。
 * version 要外露:前端拿到後,下次修改時帶回來做樂觀鎖比對。
 */
public record CategoryResponse(Integer categoryId, String name, Integer sortOrder, Integer version) {

    public static CategoryResponse from(Category c) {
        return new CategoryResponse(c.getCategoryId(), c.getName(), c.getSortOrder(), c.getVersion());
    }
}
