## 1. WebSocket 基礎設施

- [x] 1.1 WebSocketConfig — STOMP + SockJS 設定，JWT 認證
- [x] 1.2 WebSocket DTO — BalanceMessage, SettlementMessage, ChatMessage

## 2. 推送整合

- [x] 2.1 NotificationService — 封裝 SimpMessagingTemplate 推送邏輯
- [x] 2.2 OrderService — createUserOrder / deleteUserOrder 後推送餘額更新
- [x] 2.3 settleOrder — 結算成功推送扣款通知
- [x] 2.4 settleOrder — 結算失敗推送 FAILED 通知

## 3. 聊天

- [x] 3.1 ChatController — 接收聊天訊息，廣播到 /topic/order/{orderId}/chat

## 4. 測試

- [x] 4.1 NotificationServiceTest (3 tests) — balance update, settlement success/failed
- [x] 4.2 OrderServiceTest 更新 — mock NotificationService
