## Context

目前 `NotificationService` 透過 Spring 的 `SimpMessagingTemplate.convertAndSendToUser()` 直接把訊息推送到 WebSocket client。SimpleBroker 把「誰連在哪個 session」的資訊存在各實例的記憶體 map 裡。

多實例部署下的問題：
- 使用者 alice 的 WebSocket 連到實例 A
- 使用者 alice 在實例 B 下單 → `sendBalanceUpdate` 在實例 B 執行
- 實例 B 的 SimpleBroker 找不到 alice 的 session，訊息直接被丟掉
- alice 沒收到通知

Phase 8 已完成 Redis 基礎建設，可直接使用。

## Goals / Non-Goals

**Goals:**
- 跨實例的 WebSocket 通知廣播（alice 連哪台都能收到）
- 保留現有 STOMP endpoint、JWT 驗證、client subscription 路徑不變
- `NotificationService` 對外 API 不變（sendBalanceUpdate / sendSettlementSuccess / sendSettlementFailed）

**Non-Goals:**
- 不改用外部 STOMP broker（RabbitMQ / ActiveMQ）— 太重
- 不做訊息持久化 / 離線訊息 — Redis Pub/Sub fire-and-forget
- 不改 chat 訊息流程（`/topic/order/{orderId}/chat`） — 目前 chat 由 Controller 直接 broadcast，SimpleBroker 保留即可；只處理 user-specific queue

## Decisions

### 1. Redis Pub/Sub 作為跨實例傳輸層

**選擇：** 所有實例 `SUBSCRIBE websocket:notifications`，發通知時 `PUBLISH` 到此 channel

**替代方案：** Redis Stream（有持久化、consumer group）

**理由：** Pub/Sub 更簡單，符合 fire-and-forget 語意。通知訊息不需要保留（使用者當下不在線就算了，下次登入有其他方式同步狀態）。Stream 功能更強但複雜度高。

### 2. 訊息格式

發布到 Redis 的 envelope：

```json
{
  "userId": "alice",
  "destination": "/queue/balance",
  "payload": { "type": "BALANCE_UPDATED", "availableBalance": 4500, "reason": "下單" }
}
```

- `userId`：目標使用者（subscriber 用它決定要不要處理）
- `destination`：相對路徑，subscriber 組出 `/user/{userId}{destination}`
- `payload`：原本要送的 DTO（BalanceMessage / SettlementMessage）

### 3. 保留 SimpleBroker，只改 Service 層

**選擇：** `WebSocketConfig` 繼續用 `enableSimpleBroker("/topic", "/user/queue")`。新增 `RedisWebSocketRelay` 元件：
- 啟動時 subscribe Redis channel
- 收到訊息後呼叫 `messagingTemplate.convertAndSendToUser(userId, destination, payload)`
- 如果該使用者不在這台實例，Spring 會自動丟掉（這正是我們要的）

**替代方案：** 完全取代 SimpleBroker 用自訂的 MessageHandler

**理由：** 最小改動。SimpleBroker 本身只處理本地 session 表，我們只是讓每個實例都收到「該發通知」的指令，由 SimpleBroker 決定要不要發（有該 user 的 session 才發）。

### 4. NotificationService 改為 publish

原本：
```java
messagingTemplate.convertAndSendToUser(userId, "/queue/balance", msg);
```

改為：
```java
redisPublisher.publish(new Envelope(userId, "/queue/balance", msg));
```

`RedisWebSocketRelay`（每台實例都有一個）收到 envelope 後會呼叫 `convertAndSendToUser`。包括訊息的「發源實例」也會收到自己發的訊息，但這沒問題——如果該 user 剛好連在這台，就正常送出。

### 5. Chat 訊息不經過 Redis

Chat 訊息走 `/topic/order/{orderId}/chat`，目前由 client 送到 `/app/...`，Controller 處理後廣播到 `/topic/...`。`/topic` 是多人訂閱（不綁定特定 user），SimpleBroker 的 topic subscription 表也是單實例的，嚴格來說也會有跨實例問題。

但這次 change 先不處理 chat（proposal 也沒列進去）——只解決 user-specific notification 的跨實例問題。Chat 跨實例若需要可後續再加。

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| 所有實例都收到 envelope，有 N 倍 CPU 消耗 | 通知量不大（下單 / 結算才觸發），可接受 |
| Redis 掛掉時通知不會送出 | 通知本來就是 best-effort，非關鍵功能；Redis 恢復後續通知正常 |
| Client 連線斷掉時訊息丟失 | Pub/Sub fire-and-forget，本來就沒保證送達；不影響業務正確性（餘額等狀態以 DB 為準） |
| Envelope 序列化/反序列化成本 | 用 Jackson + 泛型 payload，每則訊息很小（< 1KB），成本可忽略 |
