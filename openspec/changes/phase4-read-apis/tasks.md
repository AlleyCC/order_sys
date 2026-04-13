## 1. Entity + Enum

- [x] 1.1 Store, Menu, Order, OrderItem, Transaction entity
- [x] 1.2 OrderStatus, TradeType enum

## 2. Mapper

- [x] 2.1 StoreMapper, MenuMapper, OrderMapper, OrderItemMapper, TransactionMapper
- [x] 2.2 OrderMapper.xml — get_order_detail JOIN 查詢

## 3. DTO

- [x] 3.1 OrderDetailResponse, OrderItemResponse, TransactionResponse

## 4. 測試 (TDD)

- [x] 4.1 GET /order/get_all_shops — 不需認證, 回傳 3 家店
- [x] 4.2 GET /order/get_all_orders — ��認證, 回傳 2 筆訂��
- [x] 4.3 GET /order/get_order_detail — 含 orderItems + userName, 404 not found
- [x] 4.4 GET /order/get_user_account — balance + availableBalance, 400 missing param
- [x] 4.5 GET /user/get_user_transaction_record — RECHARGE 正數, DEBIT 負數

## 5. Service + Controller

- [x] 5.1 OrderService + OrderController (get_all_shops, get_all_orders, get_order_detail, get_user_account)
- [x] 5.2 UserService + UserController (get_user_transaction_record)
- [x] 5.3 ResourceNotFoundException + GlobalExceptionHandler 擴充
