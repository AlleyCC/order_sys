## Why

目前使用 Java `DelayQueue`（in-memory）做訂單到期自動結算。多實例部署時，每個實例都會從 DB 載入所有 OPEN 訂單並各自排程，導致同一筆訂單被重複結算。

需要改用 Redis Sorted Set 作為共享的延遲佇列，所有實例共用一份，搭配原子操作防止重複消費。

前置條件：phase8-redis-token 已完成（Redis 基礎建設已就緒）。

## What Changes

### Redis Sorted Set 取代 DelayQueue
- 建立 sorted set key `order:settlement:queue`
- score = deadline 的 epoch milliseconds，member = orderId
- 建立訂單時 `ZADD`，取消訂單時 `ZREM`

### Polling Consumer 取代 Daemon Thread
- 使用 `@Scheduled(fixedRate = 1000)` 每秒輪詢
- `ZRANGEBYSCORE key 0 <now> LIMIT 0 10` 撈到期訂單
- 對每筆訂單先 `ZREM` 成功才處理（原子操作，防止多實例重複消費）
- 處理失敗時重新 `ZADD` 回去（retry 機制）

### 移除舊元件
- 移除 `OrderSettlementQueue`（Java DelayQueue wrapper）
- 移除 `OrderSettlementTask`（Delayed implementation）
- 移除 `OrderSettlementConsumer`（daemon thread）
- 移除啟動時的 `recoverFromDb()` 邏輯（Redis 本身就是持久化的佇列）

## Capabilities

### New Capabilities
- `redis-settlement-queue`: 基於 Redis Sorted Set 的分散式延遲佇列

### Modified Capabilities
- `order-create`: 改為 ZADD 到 Redis
- `order-cancel`: 改為 ZREM 從 Redis
- `order-settlement`: 改為從 Redis 消費到期訂單

## Impact

- 刪除 3 個類別：`OrderSettlementQueue`、`OrderSettlementTask`、`OrderSettlementConsumer`
- 新增：`RedisSettlementQueue`（ZADD/ZREM 操作）、`RedisSettlementConsumer`（@Scheduled polling）
- 修改：`OrderService` 中 `settlementQueue.add/remove` 改為呼叫新元件
- 測試：更新 settlement 相關測試，使用 Testcontainers Redis
- **不影響**：結算邏輯本身（`settleOrder`、`payOrder`）、其他 API
