## 1. RedisSettlementQueue

- [x] 1.1 新增 `RedisSettlementQueue`，注入 `StringRedisTemplate`，定義 key 常數 `order:settlement:queue`
- [x] 1.2 實作 `add(orderId, deadline)` — `ZADD` score 為 deadline epoch ms
- [x] 1.3 實作 `remove(orderId)` — `ZREM` 回傳是否成功移除
- [x] 1.4 實作 `pollDueOrders(now)` — `ZRANGEBYSCORE 0 <now> LIMIT 0 10` 回傳 orderId 列表

## 2. RedisSettlementConsumer

- [x] 2.1 新增 `RedisSettlementConsumer`，注入 `RedisSettlementQueue` + `OrderService`
- [x] 2.2 實作 `@Scheduled(fixedRate = 1000)` polling 方法：呼叫 `pollDueOrders` → 逐筆 `remove` → 成功則 `settleOrder`
- [x] 2.3 加入失敗重新入隊邏輯：`settleOrder` 拋例外時重新 `ZADD`
- [x] 2.4 加入 `@ConditionalOnProperty(name = "app.scheduler.enabled")` 條件啟用

## 3. OrderService 修改

- [x] 3.1 將 `OrderSettlementQueue` 依賴替換為 `RedisSettlementQueue`
- [x] 3.2 `createOrder()` 改為呼叫 `redisSettlementQueue.add(orderId, deadline)`
- [x] 3.3 `deleteUserOrder()` 改為呼叫 `redisSettlementQueue.remove(orderId)`
- [x] 3.4 `cancelOrder()` 改為呼叫 `redisSettlementQueue.remove(orderId)`

## 4. 移除舊元件

- [x] 4.1 刪除 `OrderSettlementQueue.java`
- [x] 4.2 刪除 `OrderSettlementTask.java`
- [x] 4.3 刪除 `OrderSettlementConsumer.java`
- [x] 4.4 刪除 `OrderSettlementQueueTest.java`
- [x] 4.5 刪除 `OrderSettlementTaskTest.java`

## 5. 測試

- [x] 5.1 `RedisSettlementQueue` 整合測試：ZADD / ZREM / ZRANGEBYSCORE + TTL 驗證（Testcontainers Redis）
- [x] 5.2 `RedisSettlementConsumer` 單元測試：mock `RedisSettlementQueue` + `OrderService`，驗證 polling → claim → settle 流程
- [x] 5.3 `RedisSettlementConsumer` 單元測試：驗證 settle 失敗時重新入隊
- [x] 5.4 `OrderServiceTest` 更新：mock `RedisSettlementQueue` 取代 `OrderSettlementQueue`
- [x] 5.5 `OrderSettleIntegrationTest` 更新：移除舊 queue 依賴，確認整合測試仍通過
- [x] 5.6 執行 `./mvnw test` 確認所有測試通過
