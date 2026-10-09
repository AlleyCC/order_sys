# Design: settlement-notification-inbox

## Context

動機見 proposal.md「Why」。這裡只列會影響做法的現況:

- `PaymentService.executePayment` 是 `@Transactional`:先條件式寫入 `CLOSED/FAILED → SETTLED` 搶下訂單,再逐人 `casDebit` 並寫 `transactions`;第一個 `casDebit` 回傳 0 就拋 `InsufficientBalanceException`,整個交易回滾
- `OrderService.payOrder` 沒有交易:接到 `InsufficientBalanceException` 後用 `updateById` **無條件**把訂單寫成 `FAILED`,再往外拋
- `OrderService.settleOrder`(自動結算)在 `payOrder` 返回或拋出後才呼叫 `notifySettlementResult` 推播;手動 `pay_order` 直接呼叫 `payOrder`,所以**沒有任何推播**
- `notifySettlementAbandoned` 由 `RedisSettlementConsumer.notifyAbandoned` 呼叫,屬於 at-least-once:`claimAbandoned` 之後若拋例外會 `markAbandoned`,下一輪重跑
- 訂單為 `FAILED` 時,參與者的金額仍計入凍結(`OrderItemMapper.getFrozenAmount` 的 `status IN ('OPEN','CLOSED','FAILED')`)
- `DynamicAuthorizationManager`:未登記在 `resources` 的路徑只需要登入,所以新 API 不需要 RBAC migration

## Goals / Non-Goals

**Goals:**

- 通知存在與否,永遠和它描述的狀態變化一致:扣款成功就一定有成功通知,扣款回滾就一定沒有
- 程式在任何時間點當掉,都不會出現「狀態變了但沒通知」;重送也不會產生重複的 abandoned 通知
- 推播失敗不影響結算結果

**Non-Goals:**

- 保證推播送達(由「重連時查詢未讀」補齊)
- 通知內容的多語系或樣板化

## Decisions

### D1. 收件匣:新表 `notifications`,不沿用 `transactions`

```sql
CREATE TABLE `notifications` (
  `notification_id` VARCHAR(36)  NOT NULL COMMENT 'UUID',
  `user_id`         VARCHAR(20)  NOT NULL COMMENT '收件人',
  `order_id`        VARCHAR(36)  DEFAULT NULL,
  `type`            VARCHAR(30)  NOT NULL,
  `content`         VARCHAR(255) NOT NULL COMMENT '寫入當下的完整訊息',
  `dedup_key`       VARCHAR(64)  DEFAULT NULL COMMENT '只有需要去重的類型才填',
  `read_at`         DATETIME     DEFAULT NULL COMMENT 'NULL = 未讀',
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '毫秒:同一次結算寫入多則,未讀排序才穩定',
  PRIMARY KEY (`notification_id`),
  UNIQUE KEY `uk_user_dedup` (`user_id`, `dedup_key`),
  KEY `idx_user_unread` (`user_id`, `read_at`, `created_at`),
  CONSTRAINT `fk_notifications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

`type`(VARCHAR,依專案慣例):

| type | 收件人 | 何時寫入 |
|---|---|---|
| `SETTLEMENT_SUCCEEDED` | 每位參與者(團主除外) | 扣款交易內 |
| `SETTLEMENT_SUCCEEDED_OWNER` | 團主 | 扣款交易內 |
| `SETTLEMENT_INSUFFICIENT` | 餘額不足的參與者(團主除外) | `FAILED` 交易內 |
| `SETTLEMENT_BLOCKED` | 餘額足夠的參與者(團主除外) | `FAILED` 交易內 |
| `SETTLEMENT_FAILED_OWNER` | 團主 | `FAILED` 交易內 |
| `SETTLEMENT_ABANDONED` | 團主 + 救援角色 | `notifySettlementAbandoned` |

- **不沿用 `transactions`**:那是帳本,只記金額變動;餘額不足與 abandoned 沒有金額變動,塞進去會汙染對帳
- **`content` 存寫入當下的完整文字**:之後改文案不會改到歷史通知
- **`order_id` 不加外鍵**:通知是歷史紀錄,不應該擋住訂單的任何操作
- **`dedup_key` 用 NULL 表示不去重**:MySQL 的 UNIQUE 允許多個 NULL,所以成功/失敗通知不受影響,只有 abandoned 填 `ABANDONED:{orderId}`

替代方案:用 `(user_id, order_id, type)` 當唯一鍵。否決,因為會擋掉「每次失敗都發一則」。

### D2. 扣款迴圈:先試扣全部,再決定成敗

```
for each 參與者:
    casDebit 成功 → 寫 transaction、記下要寫的通知
    casDebit 失敗 → 加入 shortUserIds,繼續下一位
if shortUserIds 非空:
    throw InsufficientBalanceException(shortUserIds)   ← 整個交易回滾
else:
    寫入所有 SETTLEMENT_SUCCEEDED 通知                  ← 同一個交易
    寫入一則 SETTLEMENT_SUCCEEDED_OWNER 給團主(總金額)
```

團主同時是參與者時,只寫 `SETTLEMENT_SUCCEEDED_OWNER`,內容附上團主自己被扣的金額與扣款後餘額,規則和 D3 失敗時一致。

- 已試扣成功的人,在回滾前外界看不到餘額變化,不會被真的扣款
- 代價是失敗時多跑幾次 `casDebit`,並且持有那些人的行鎖直到回滾。參與者人數是團購規模(個位數到數十人),可以接受

替代方案:扣款前先 SELECT 所有人餘額。否決,因為 SELECT 和扣款之間餘額可能變動,扣款時還是要檢查一次,等於同一個判斷寫兩遍。

### D3. `FAILED` 和通知包成一個新交易,並改為條件式寫入

新增 `PaymentService.markFailed(order, shortUserIds)`(`@Transactional`):

1. `updateStatusIfIn(orderId, FAILED, [CLOSED, FAILED])`
2. 回傳 0 → 狀態已被別人改掉(例如另一個結算剛好成功),**不寫通知**,直接返回
3. 回傳 1 → 依 `shortUserIds` 寫入 `SETTLEMENT_INSUFFICIENT` / `SETTLEMENT_BLOCKED`,並寫一則 `SETTLEMENT_FAILED_OWNER` 給團主(`orders.created_by`),內容列出所有餘額不足者

團主同時是參與者時,只寫 `SETTLEMENT_FAILED_OWNER`,不再寫參與者那則:團主版已經包含「誰不夠」的完整資訊(包括團主自己),收兩則只是重複。

改為條件式寫入的理由:通知要和「真的發生的狀態變化」一致。原本無條件的 `updateById` 可能把別人剛寫好的 `SETTLED` 蓋回 `FAILED`,這時再發「結算失敗」的通知就是錯的。

`FAILED → FAILED` 也算一次狀態變化:團主重試、再失敗,會產生新的一批通知(proposal「每次失敗都發一則」)。

放在 `PaymentService`,不放 `OrderService`:`payOrder` 呼叫同一個 bean 的 `@Transactional` 方法時,不會經過 proxy,交易不會生效。

### D4. Abandoned:沒有可以綁的交易,改用唯一鍵去重

`notifySettlementAbandoned` 對每位收件人:

1. 寫入 `SETTLEMENT_ABANDONED`,`dedup_key = ABANDONED:{orderId}`
2. 撞到 `uk_user_dedup`(`DuplicateKeyException`)→ 視為已存在,**不推播**
3. 寫入成功 → 推播

每一筆寫入本身就是原子的,所以不需要交易包住整批。中途當掉時,重送會補上缺的、跳過已有的。已存在的不推播,是因為那次推播可能已經送過了;就算沒送過,收件人重連時查未讀也會看到。

替代方案:在 Redis 記錄「哪些人已通知」。否決,因為那就變成 Redis 和 DB 兩邊要一致,又回到原本的問題。

### D5. 推播移到 `payOrder`,在 commit 之後送

```
payOrder
 ├─ executePayment()      [tx: SETTLED + 扣款 + transactions + 通知] → commit
 │    └─ 回傳這次寫入的通知清單
 ├─ 成功 → 依清單推播(附 notificationId)
 └─ InsufficientBalanceException
      ├─ markFailed()      [tx: FAILED + 通知] → commit
      ├─ 依清單推播
      └─ 往外拋(手動 pay_order 回 400;settleOrder 照舊 catch)
```

- 推播在交易方法回傳之後才執行,所以一定在 commit 之後,不需要 `@TransactionalEventListener`
- `settleOrder` 移除 `notifySettlementResult`,改由 `payOrder` 統一處理,這樣手動 `pay_order` 也會推播
- 推播包在 try/catch 裡,失敗只記 log,和 `BalanceChangeNotifier` 的做法一致

### D6. API

依專案既有命名(`/user/get_user_transaction_record`):

| Method | Path | 說明 |
|---|---|---|
| GET | `/user/get_unread_notifications?page=&size=` | 自己的未讀通知,依 `created_at` 新到舊,分頁 |
| POST | `/user/read_notification` `{notificationId}` | 將單則標為已讀 |

- 收件人一律取自 token(`@AuthenticationPrincipal`),不接受 userId 參數
- 標為已讀:`UPDATE ... SET read_at = NOW() WHERE notification_id = ? AND user_id = ? AND read_at IS NULL`
  - 影響 1 列 → 200
  - 影響 0 列 → 再查 `notification_id + user_id`:存在(已經讀過)→ 200(冪等);不存在 → 404
  - 別人的通知也回 404,不回 403,避免洩漏「這個 id 存在」

### D7. 推播內容

`SettlementMessage` 新增 `notificationId` 欄位。前端用它和 `get_unread_notifications` 的結果去重(重連時推播和查詢可能同時到)。既有的 `result` 欄位保留。

## Risks / Trade-offs

- [扣款交易內多了 N 筆 INSERT,持鎖時間變長] → INSERT 是同一個 DB 連線上的本地操作,不是外部 I/O;N 是團購人數,影響很小
- [`markFailed` 條件式寫入失敗時不發通知] → 這代表另一個結算已改變狀態,該結算會負責自己的通知
- [`markFailed` 本身失敗(DB 斷線)] → 訂單停在 `CLOSED`、沒有通知;自動結算會重試,手動 `pay_order` 會回 500 由團主重試。和現況相同,沒有變差
- [abandoned 重送時已存在的通知不推播] → 收件人可能少收到一次即時推播,但重連查未讀一定看得到
- [通知表只增不減] → 目前不做清理;資料量是「每次結算 × 參與人數」,短期內不需要處理
- [`pay_order` 錯誤訊息改列出所有餘額不足的 userId] → 前端若有解析舊訊息格式需要調整;目前錯誤訊息本來就含 userId,沒有新增洩漏

## Migration Plan

1. `V10__add_notifications.sql` 建表,純新增,不影響既有資料
2. 上線前已發生的結算沒有通知紀錄,不回補
3. 回滾:移除程式後,表可以保留不用;不需要 down migration
