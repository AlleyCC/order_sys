## Context

目前訂單到期自動結算使用 Java `DelayQueue`（in-memory），由 `OrderSettlementQueue` + `OrderSettlementTask` + `OrderSettlementConsumer` 三個類別組成。啟動時透過 `recoverFromDb()` 載入所有 OPEN 訂單。

問題：多實例部署時每個實例各自從 DB 載入並排程，導致同一筆訂單被重複結算。

Phase 8 已完成 Redis 基礎建設（docker-compose、spring-boot-starter-data-redis、Testcontainers），可直接使用。

## Goals / Non-Goals

**Goals:**
- 用 Redis Sorted Set 取代 Java DelayQueue，實現分散式共享佇列
- 用 `@Scheduled` polling 取代 daemon thread 消費
- 用 `ZREM` 原子操作防止多實例重複消費
- 處理失敗時重新入隊（retry）
- 移除舊的 3 個 scheduler 類別

**Non-Goals:**
- 不改變結算邏輯本身（`settleOrder`、`payOrder`、`PaymentService`）
- 不做 dead letter queue 或 retry 次數上限
- 不做 Redis cluster 或 sentinel 配置

## Decisions

### 1. Redis Sorted Set 作為延遲佇列

**選擇：** `ZADD order:settlement:queue <deadline_epoch_ms> <orderId>`

**替代方案：** Redis Stream + XREADGROUP

**理由：** Sorted Set 更簡單，score 天然支援時間排序，`ZRANGEBYSCORE` + `ZREM` 組合即可實現到期消費。Redis Stream 功能更強但複雜度高，對這個場景 overkill。

### 2. Polling 消費（@Scheduled）取代阻塞式 take()

**選擇：** `@Scheduled(fixedRate = 1000)` 每秒輪詢

**替代方案：** 維持 daemon thread + sleep loop

**理由：** `@Scheduled` 由 Spring 管理生命週期，不需要手動管理 thread。fixedRate=1000 對訂單結算場景延遲可接受（最多 1 秒）。

### 3. ZREM 原子性防重複消費

**流程：**
1. `ZRANGEBYSCORE key 0 <now> LIMIT 0 10` 撈到期訂單
2. 對每筆訂單執行 `ZREM key <orderId>`，回傳 1 表示搶到
3. 只有 ZREM 成功的實例才執行 `settleOrder()`

**理由：** `ZREM` 是 Redis 原子操作，同一個 member 只會被一個 client 成功移除，天然防重複。

### 4. 失敗重新入隊

**選擇：** `settleOrder()` 拋例外時，重新 `ZADD` 原 orderId + 原 deadline 回佇列

**理由：** 簡單可靠。訂單結算可能因為暫時性問題失敗（如 DB 連線），重新入隊讓下一輪 polling 重試。

### 5. 移除 recoverFromDb()

**選擇：** 不再需要啟動時從 DB 恢復

**理由：** Redis 本身就是持久化的佇列，重啟後資料仍在。不像 Java DelayQueue 是 in-memory 會丟失。

### 6. 新類別結構

| 舊類別 | 新類別 | 說明 |
|--------|--------|------|
| `OrderSettlementQueue` | `RedisSettlementQueue` | ZADD / ZREM / ZRANGEBYSCORE |
| `OrderSettlementTask` | （移除） | 不需要 Delayed 實作 |
| `OrderSettlementConsumer` | `RedisSettlementConsumer` | @Scheduled polling |

`OrderService` 中的 `settlementQueue.add()` / `remove()` 改為注入 `RedisSettlementQueue`。

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| Redis 掛掉時佇列不可用 | 訂單仍存在 DB，可手動觸發結算或等 Redis 恢復。目前單機部署，風險低 |
| Polling 有最多 1 秒延遲 | 對訂單結算場景可接受，不需要毫秒級精確 |
| 失敗重試無上限 | Non-goal，若需要可後續加 retry counter。目前結算失敗會標記 FAILED，不會無限重試 |
| ZRANGEBYSCORE 每次最多 10 筆 | 高峰期可能堆積，但每秒 polling 持續消化，不會丟失 |
