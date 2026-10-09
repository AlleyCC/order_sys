# Tasks: settlement-notification-inbox

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
交易邊界與資料是否寫入,用整合測試驗證(`@SpringBootTest` + Testcontainers,沿用 `OrderSettleIntegrationTest` 的設定);
`OrderService` 的流程分支(呼叫順序、推播與否),用 `OrderServiceTest`(Mockito)驗證。

## 1. 資料表與 Mapper

- [x] 1.1 新增 `V10__add_notifications.sql`,建立 `notifications` 表(design.md D1);`./mvnw flyway:migrate` 後以 `SHOW CREATE TABLE notifications` 確認唯一鍵與索引
- [x] 1.2 新增 `NotificationType` enum(`SETTLEMENT_SUCCEEDED`、`SETTLEMENT_SUCCEEDED_OWNER`、`SETTLEMENT_INSUFFICIENT`、`SETTLEMENT_BLOCKED`、`SETTLEMENT_FAILED_OWNER`、`SETTLEMENT_ABANDONED`)、`Notification` entity、`NotificationMapper`
- [x] 1.3 (RED)`NotificationMapperTest`:同一 `user_id` 寫入兩筆相同 `dedup_key` → 第二筆拋 `DuplicateKeyException`;兩筆 `dedup_key` 皆為 NULL → 都能寫入
- [x] 1.4 (RED)`NotificationMapperTest`:查詢未讀只回傳指定使用者、`read_at IS NULL` 的通知,依 `created_at` 新到舊
- [x] 1.5 (RED)`NotificationMapperTest`:標為已讀的條件式更新(`notification_id + user_id + read_at IS NULL`),別人的通知影響 0 列
- [x] 1.6 (GREEN)實作 1.4、1.5 需要的查詢

## 2. 扣款找出所有餘額不足者(spec:結算維持全有全無並找出所有餘額不足者)

- [x] 2.1 `InsufficientBalanceException` 新增攜帶 `List<String> shortUserIds` 的建構子,訊息列出所有人;既有下單時的單一訊息建構子保留
- [x] 2.2 (RED)整合測試:三人中兩人不足 → 例外的 `shortUserIds` 恰為這兩人;三人餘額、`transactions` 筆數皆未變
- [x] 2.3 (GREEN)`PaymentService.executePayment` 迴圈改為收集不足者,迴圈結束後才拋出(design.md D2)

## 3. 結算成功的通知(spec:結算成功的通知與扣款一起成立)

- [x] 3.1 (RED)整合測試:團主未參與 → 每位參與者一則 `SETTLEMENT_SUCCEEDED`(內容含金額與餘額)、團主一則 `SETTLEMENT_SUCCEEDED_OWNER`(含總金額)
- [x] 3.2 (RED)整合測試:團主同時是參與者 → 團主只有一則 `SETTLEMENT_SUCCEEDED_OWNER`,內容含本人金額與餘額
- [x] 3.3 (RED)整合測試:扣款交易中途拋例外(例如餘額不足)→ `notifications` 沒有任何 `SETTLEMENT_SUCCEEDED*`
- [x] 3.4 (GREEN)`executePayment` 在同一交易內寫入成功通知,並回傳本次寫入的通知清單(供 commit 後推播)

## 4. 結算失敗的通知(spec:結算失敗的通知與訂單轉為失敗一起成立、每次結算失敗都產生新通知)

- [x] 4.1 (RED)整合測試:小明不足、小華足夠、團主未參與 → 小明 `SETTLEMENT_INSUFFICIENT`、小華 `SETTLEMENT_BLOCKED`、團主 `SETTLEMENT_FAILED_OWNER`(內容列出小明);訂單為 `FAILED`
- [x] 4.2 (RED)整合測試:團主參與且不足 → 團主只有一則 `SETTLEMENT_FAILED_OWNER`,內容列出團主
- [x] 4.3 (RED)整合測試:訂單已是 `SETTLED` 時呼叫 `markFailed` → 訂單仍為 `SETTLED`、沒有寫入任何失敗通知
- [x] 4.4 (RED)整合測試:同一訂單結算失敗兩次 → 小明有兩則 `SETTLEMENT_INSUFFICIENT`
- [x] 4.5 (GREEN)新增 `PaymentService.markFailed(order, shortUserIds)`(`@Transactional`,條件式寫入 `FAILED`,design.md D3),回傳寫入的通知清單

## 5. 推播移到 payOrder(spec:手動結算與自動結算的通知行為一致、推播盡力送達且不影響結算)

- [x] 5.1 `SettlementMessage` 新增 `notificationId`;`NotificationService` 新增「依通知清單推播」的方法
- [x] 5.2 (RED)`OrderServiceTest`:`payOrder` 成功 → 依 `executePayment` 回傳的清單推播
- [x] 5.3 (RED)`OrderServiceTest`:`payOrder` 遇 `InsufficientBalanceException` → 呼叫 `markFailed`、依其回傳清單推播、再往外拋;不再呼叫 `orderMapper.updateById`
- [x] 5.4 (RED)`OrderServiceTest`:推播拋例外 → `payOrder` 仍正常返回(成功時)或仍拋原本的 `InsufficientBalanceException`(失敗時)
- [x] 5.5 (GREEN)改寫 `OrderService.payOrder`(design.md D5)
- [x] 5.6 (REFACTOR)`settleOrder` 移除 `notifySettlementResult` 與該私有方法;改寫 `OrderServiceTest` 中依賴舊推播路徑的案例(例如 `sendSettlementFailed` 的 verify)
- [x] 5.7 (RED → GREEN)`OrderControllerTest`:手動 `pay_order` 兩人不足 → 400,`detail` 列出兩人;成功 → 參與者與團主都有通知
- [x] 5.8 (RED → GREEN)`OrderServiceTest`:扣款成功後每位參與者各推一次可用餘額(`/user/queue/balance`);推播失敗不影響結果;餘額不足不推(補上 SPEC 原本寫了、程式沒做的部分)

## 6. 自動重試用完的通知(spec:自動重試用完的通知不重複)

- [x] 6.1 (RED)整合測試:`notifySettlementAbandoned` 執行兩次 → 團主與每位救援管理員各恰一則 `SETTLEMENT_ABANDONED`
- [x] 6.2 (RED)`OrderServiceTest`:寫入撞到 `DuplicateKeyException` → 不推播該收件人,其他收件人照常
- [x] 6.3 (RED)整合測試:訂單已 `SETTLED` → 不寫入任何通知(沿用既有判斷)
- [x] 6.4 (GREEN)改寫 `notifySettlementAbandoned`:先寫入(`dedup_key = ABANDONED:{orderId}`),成功才推播(design.md D4)

## 7. 未讀查詢與標為已讀 API(spec:查詢自己的未讀通知、將單則通知標為已讀)

- [x] 7.1 (RED)`UserControllerTest`:`GET /user/get_unread_notifications` 只回傳自己的未讀,新到舊、分頁;未登入 401
- [x] 7.2 (RED)`UserControllerTest`:`POST /user/read_notification` 標自己的 → 200,再查未讀不含該則;重複標 → 200
- [x] 7.3 (RED)`UserControllerTest`:標別人的或不存在的 → 404,別人的通知仍為未讀
- [x] 7.4 (GREEN)新增 service 方法與 `UserController` 兩個端點(design.md D6)

## 8. 收尾

- [x] 8.1 `./mvnw test` 全部通過,包含既有的 `OrderConcurrentSettleTest`、`SettlementLeaseScenarioTest`
- [x] 8.2 更新 `docs/SPEC.md`:新增 2 支 API、`notifications` 表、結算流程(7.3)的通知寫入時機、`pay_order` 餘額不足的錯誤訊息
