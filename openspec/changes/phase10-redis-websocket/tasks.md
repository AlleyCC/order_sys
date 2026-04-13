## 1. Notification Envelope DTO

- [x] 1.1 新增 `dto/websocket/NotificationEnvelope.java`，含 `userId` / `destination` / `payload`（Object）欄位

## 2. Redis Publisher

- [x] 2.1 新增 `NotificationPublisher`，注入 `StringRedisTemplate` + `ObjectMapper`
- [x] 2.2 實作 `publish(userId, destination, payload)` — 組 envelope → JSON → `PUBLISH websocket:notifications`
- [x] 2.3 定義 channel 常數 `websocket:notifications`

## 3. Redis Subscriber Relay

- [x] 3.1 新增 `RedisWebSocketRelay`（`MessageListener` 實作），注入 `SimpMessagingTemplate` + `ObjectMapper`
- [x] 3.2 `onMessage` 解析 envelope → 呼叫 `messagingTemplate.convertAndSendToUser(userId, destination, payload)`
- [x] 3.3 解析失敗時只 log warn，不拋例外（防止 relay 掛掉）

## 4. Redis Subscriber Config

- [x] 4.1 新增 `RedisSubscriberConfig`：建立 `RedisMessageListenerContainer` bean
- [x] 4.2 將 `RedisWebSocketRelay` 註冊為 listener，訂閱 `websocket:notifications` channel

## 5. NotificationService 改造

- [x] 5.1 `NotificationService` 移除 `SimpMessagingTemplate` 依賴，改注入 `NotificationPublisher`
- [x] 5.2 `sendBalanceUpdate` 改為呼叫 `publisher.publish(userId, "/queue/balance", msg)`
- [x] 5.3 `sendSettlementSuccess` 改為呼叫 `publisher.publish(userId, "/queue/notification", msg)`
- [x] 5.4 `sendSettlementFailed` 改為呼叫 `publisher.publish(userId, "/queue/notification", msg)`

## 6. 測試

- [x] 6.1 `NotificationPublisher` 單元測試：mock `StringRedisTemplate`，驗證 publish 的 channel 與 JSON 內容
- [x] 6.2 `RedisWebSocketRelay` 單元測試：mock `SimpMessagingTemplate`，餵入 envelope JSON，驗證 convertAndSendToUser 參數正確
- [x] 6.3 `RedisWebSocketRelay` 單元測試：格式錯誤的 JSON 不拋例外
- [x] 6.4 `NotificationServiceTest` 更新：改為 mock `NotificationPublisher`（取代 `SimpMessagingTemplate`）
- [x] 6.5 跨實例整合測試（Testcontainers Redis）：publisher + relay 端對端驗證訊息能透過 Redis 送達
- [x] 6.6 執行 `./mvnw test` 確認所有測試通過
