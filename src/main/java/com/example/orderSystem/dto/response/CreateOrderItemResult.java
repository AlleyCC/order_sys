package com.example.orderSystem.dto.response;

/**
 * 下單結果。replayed = true 表示這是同一個 Idempotency-Key 的重送,
 * itemId 是第一次寫入的品項,這次沒有寫入任何東西。
 */
public record CreateOrderItemResult(Integer itemId, boolean replayed) {
}
