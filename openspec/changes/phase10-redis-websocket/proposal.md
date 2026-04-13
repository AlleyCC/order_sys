## Why

目前 WebSocket 使用 Spring 內建的 `SimpleBroker`，它把「誰連在哪」的資訊存在各實例的記憶體裡。多實例部署時，實例 A 發的通知只能送到連在實例 A 的使用者，連在實例 B 的使用者收不到。

需要把 broker 換成 Redis Pub/Sub，讓通知訊息能跨實例廣播。

前置條件：phase8-redis-token 已完成（Redis 基礎建設已就緒）。

## What Changes

### SimpleBroker → Redis Pub/Sub Relay
- 新增 `RedisMessageBrokerConfig`，設定 Redis Pub/Sub channel
- 發通知時：先 publish 到 Redis channel（而非直接 `messagingTemplate.convertAndSendToUser`）
- 每個實例都 subscribe 該 channel，收到後檢查目標使用者是否連在自己這台，是的話才透過 WebSocket 送出

### 替代方案考量
- **Spring 官方推薦**：外部 STOMP broker（RabbitMQ / ActiveMQ）→ 太重，不適合目前規模
- **Redis Pub/Sub**：輕量、已有 Redis、符合需求 → 採用

### NotificationService 調整
- `sendBalanceUpdate` / `sendSettlementSuccess` / `sendSettlementFailed` 改為 publish 到 Redis
- 新增 Redis subscriber 接收後轉發給本地 WebSocket 連線

## Capabilities

### Modified Capabilities
- `websocket-notification`: 從單實例 SimpleBroker 改為 Redis Pub/Sub 跨實例廣播

## Impact

- 修改：`WebSocketConfig`（新增 Redis 訂閱）
- 修改：`NotificationService`（publish 到 Redis）
- 新增：`RedisWebSocketRelay`（subscribe + 轉發）
- 測試：更新 notification 相關測試
- **不影響**：WebSocket 連線建立流程、JWT 驗證、STOMP endpoint
