## Why

Auth 模組完成後，需要實作基本的讀取 API，讓前端可以顯示店家、訂單、帳戶資訊。這些都是純查詢，不涉及狀態變更。

## What Changes

- **Entity**: Store, Menu, Order, OrderItem, Transaction
- **Mapper**: StoreMapper, MenuMapper, OrderMapper, OrderItemMapper, TransactionMapper + XML
- **DTO**: OrderDetailResponse, OrderItemResponse, TransactionResponse
- **Service**: OrderService, UserService
- **Controller**: OrderController (3 endpoints), UserController (2 endpoints)
- **Enum**: OrderStatus, TradeType

## Endpoints

| Method | Path | Auth | 說明 |
|--------|------|:----:|------|
| GET | /order/get_all_shops | No | 所有店家列表 |
| GET | /order/get_all_orders | Yes | 所有訂單列表 |
| GET | /order/get_order_detail | Yes | 訂單明細 + 品項 |
| GET | /order/get_user_account | Yes | 使用者餘額 |
| GET | /user/get_user_transaction_record | Yes | 交易紀錄 |
