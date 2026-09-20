package com.example.orderSystem.event;

/**
 * 使用者可用餘額變動。由 AFTER_COMMIT 的 listener 接手推播,
 * 確保交易若回滾,使用者不會收到「下單成功」的通知。
 */
public record BalanceChangedEvent(String userId, String reason) {
}
