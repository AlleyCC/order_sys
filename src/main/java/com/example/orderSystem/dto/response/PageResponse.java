package com.example.orderSystem.dto.response;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 統一的分頁回應契約。不直接回傳 MyBatis-Plus 的 Page
 * (它會多序列化 optimizeCountSql、orders 等內部欄位,對外契約不該綁 ORM 型別),
 * 這裡只保留前端真正需要的欄位。
 */
public record PageResponse<T>(List<T> records, long page, long size, long total, long totalPages) {

    /** 把 ORM 的分頁結果轉成對外 DTO;mapper 負責 entity → response 的逐筆轉換。 */
    public static <S, T> PageResponse<T> from(IPage<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getRecords().stream().map(mapper).toList(),
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getPages());
    }
}
