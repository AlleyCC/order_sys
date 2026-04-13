## Why

讀取 API 完成後，需要實作訂單的寫入操作 — 開團、下單、刪除、取消、結算。這些是系統核心業務。

## What Changes

- **DTO**: CreateOrderRequest, CreateOrderItemRequest, DeleteOrderItemRequest, CancelOrderRequest, PayOrderRequest
- **Exception**: ForbiddenException, InsufficientBalanceException, ConflictException
- **Service**: OrderService 新增 5 個 write methods
- **Controller**: OrderController 新增 5 個 POST endpoints
- **Mapper**: UserMapper 新增 CAS 扣款 method

## Endpoints

| Method | Path | 說明 |
|--------|------|------|
| POST | /order/create_order | 開團 |
| POST | /order/create_user_order | 下單 |
| POST | /order/delete_user_order | 刪除品項 / 取消整筆 |
| POST | /order/cancel_order | 取消訂單 |
| POST | /order/pay_order | 結算 (CAS 扣款) |

## 不含（後續 Phase）

- DelayQueue 自動排程
- WebSocket 即時通知
