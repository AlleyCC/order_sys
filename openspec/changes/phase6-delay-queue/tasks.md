## 1. Scheduler 元件

- [x] 1.1 OrderSettlementTask — implements Delayed，封裝 orderId + deadline
- [x] 1.2 OrderSettlementQueue — 管理 DelayQueue（新增/移除任務）
- [x] 1.3 OrderSettlementConsumer — 背景執行緒，消費到期任務，呼叫 OrderService.settleOrder

## 2. Service 整合

- [x] 2.1 OrderService.settleOrder — OPEN → CLOSED → payOrder（從 consumer 呼叫）
- [x] 2.2 OrderService.createOrder — 建單時加入 queue
- [x] 2.3 OrderService.cancelOrder / deleteUserOrder(all) — 取消時從 queue 移除

## 3. App 重啟恢復

- [x] 3.1 啟動時從 DB 撈 status=OPEN 的訂單，重新放入 queue

## 4. 測試

- [x] 4.1 OrderSettlementTaskTest (4 tests) — delay 計算、equals
- [x] 4.2 OrderSettlementQueueTest (4 tests) — add/remove/take
- [x] 4.3 OrderSettleIntegrationTest (2 tests) — OPEN → SETTLED、非 OPEN 跳過
