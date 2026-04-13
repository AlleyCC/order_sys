## Why

訂單建立後需要在 deadline 到達時自動結算。目前 pay_order 只支援手動觸發，缺少自動排程機制。

## What Changes

- **scheduler/OrderSettlementTask** — implements Delayed，封裝 orderId + deadline
- **scheduler/OrderSettlementQueue** — 管理 DelayQueue（新增/移除任務）
- **scheduler/OrderSettlementConsumer** — 背景執行緒，消費到期任務，觸發結算
- **OrderService** — createOrder 時加入 queue，cancelOrder 時移除
- **App 重啟恢復** — 啟動時從 DB 撈 OPEN 訂單，重新放入 queue

## 不含（後續 Phase）

- WebSocket 即時通知
