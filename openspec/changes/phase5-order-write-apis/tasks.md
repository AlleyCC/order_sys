## 1. DTO + Exception

- [x] 1.1 Request DTOs (CreateOrderRequest, CreateOrderItemRequest, DeleteOrderItemRequest)
- [x] 1.2 ForbiddenException, InsufficientBalanceException, ConflictException + GlobalExceptionHandler

## 2. Mapper

- [x] 2.1 UserMapper — CAS debit method (XML)

## 3. 測試 (TDD)

- [x] 3.1 OrderServiceTest (19 unit tests) — create_order, create_user_order, delete_user_order, cancel_order, pay_order
- [x] 3.2 OrderControllerTest (21 integration tests) — GET + POST endpoints

## 4. Service + Controller

- [x] 4.1 OrderService write methods (createOrder, createUserOrder, deleteUserOrder, cancelOrder, payOrder)
- [x] 4.2 OrderController POST endpoints (5 endpoints)
