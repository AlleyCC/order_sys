# Design: idempotent-order-item

## Context

動機見 proposal.md - Why。本節只列影響做法的現況:

- `OrderService.createUserOrder` 是 `@Transactional(isolation = READ_COMMITTED)`,流程為:驗證訂單 → 驗證菜單 → `selectForUpdate` 鎖 `users` 那一列 → 讀凍結金額 → 寫入 `order_items` → 發布 `BalanceChangedEvent`(commit 後才推播)。每個一般 SELECT 都讀最新已提交的資料。
- `GlobalExceptionHandler` 把 `DataIntegrityViolationException` 一律轉成 `409`。
- 排程目前只有 `RedisSettlementConsumer`(`@EnableScheduling` 也掛在它身上,而它受 `app.scheduler.enabled` 控制),多台機器各自執行。
- 預設 collation 是 `utf8mb4_0900_ai_ci`,不分大小寫。

## Goals / Non-Goals

**Goals:**

- 「key 已記錄」與「品項已寫入」永遠同時成立或同時不成立,不存在只有其一的中間狀態
- 同一 key 的併發請求不需要額外狀態機或逾時接手

**Non-Goals:**

- 通用的冪等框架(註解 / 切面 / filter);本變更只處理一個端點,先不抽象
- 保存完整的 HTTP 回應(status、headers、body);回放所需的資訊只有 `itemId`

## Decisions

### D1. key 記錄存 MySQL,與品項同一交易

新表 `order_item_idempotency_keys`:

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | BIGINT AUTO_INCREMENT | PK |
| user_id | VARCHAR(20) | FK → users,`ON DELETE CASCADE` |
| idempotency_key | VARCHAR(64) `CHARACTER SET ascii COLLATE ascii_bin` | 區分大小寫 |
| item_id | INT NULL | 第一次寫入的品項,與品項同一交易寫入 |
| created_at | DATETIME DEFAULT CURRENT_TIMESTAMP | 清理依據 |

`UNIQUE (user_id, idempotency_key)`、`INDEX (created_at)`。

- `item_id` **不設 FK** 到 `order_items`:品項之後可能被 `delete_user_order` 刪除,回放仍應回傳當初的結果,而不是讓刪除品項失敗或連帶刪掉記錄。
- key 欄位用 `ascii_bin`:預設 collation 不分大小寫,`"abc"` 與 `"ABC"` 會被視為同一個 key。

**替代方案:Redis `SETNX`**。不採用:Redis 與 MySQL 之間沒有原子性。DB commit 之後、寫 Redis 之前程序消失,重送就會再寫一筆,正是要解決的問題。

### D2. 交易第一步鎖 users,再「先查、最後寫入」key

```
@Transactional(READ_COMMITTED)
  ⓪ 驗證 key 格式(不合法 → 400,尚未取任何鎖)
  ① SELECT users FOR UPDATE            ← 同一使用者的所有下單在這裡排隊
  ② SELECT key WHERE (user_id, key)
       ├─ 查到 → 回放:回傳 { itemId, replayed = true }
       └─ 查不到 → ③ 原流程(驗證訂單、菜單,檢查餘額,寫品項)
                   ④ INSERT key (item_id = 新品項)
                   COMMIT
```

- **為什麼「先查再寫」在這裡是安全的**:一般情況下這是 check-then-act,兩個併發請求都會查到「不存在」。但 ① 讓同一使用者的請求排隊,輪到自己時,前一個同 key 的請求必定已經 commit 或 rollback,② 與 ④ 之間不會有同一使用者的其他請求插進來。後到者在 ① 等待,先到者成功就回放,失敗就重新執行。`UNIQUE (user_id, idempotency_key)` 保留,作為最後一道防線。
- **為什麼不用唯一索引當鎖(原設計:交易第一步就插入 key)**:key 表的 `user_id` 有 FK,`INSERT` 時 MySQL 會先對 users 那一列加共享鎖做 FK 檢查,**之後**才檢查唯一鍵。後到者因此帶著 users 的共享鎖在 key 上等待,先到者下一步 `SELECT users FOR UPDATE` 需要排他鎖,被後到者擋住,兩方互等成為死鎖。實作時以確定性實驗重現(後到者被判為死鎖犧牲者),拿掉 FK 後同一流程即不死鎖。
- **為什麼 key 在品項之後才寫**:有 ① 的排隊,key 不需要先佔位;最後才寫,`item_id` 已知,一次 INSERT 完成,不必先插入 NULL 再 UPDATE。
- **不帶 key 的請求也先鎖 users**:只保留一條流程。驗證訂單、菜單因此改在持鎖期間進行,鎖的持有時間多出兩次主鍵查詢(毫秒級);原本在鎖之後才讀凍結金額的正確性不受影響。
- **回放不重新驗證**:命中已提交的 key 就直接回傳,不再檢查訂單狀態、截止時間與餘額。回放的語意是「第一次的結果」,第一次成功了,之後訂單截止也不改變這件事。
- **回放不發布 `BalanceChangedEvent`**:餘額沒有變動。

**替代方案:拿掉 key 表 `user_id` 的 FK、維持先插入 key**。不採用:可消除上述兩方死鎖,但「三個以上同 key 請求、第一個失敗」時,等待中的請求仍會同時取得唯一鍵的共享鎖後互等;先鎖 users 則兩種死鎖都不會發生,且保留 FK。

**替代方案:先鎖 users、先插入 key,撞到重複鍵時捕捉 `DuplicateKeyException` 回放**(code review 前的實作)。不採用:有 ① 的排隊之後,先查即可得知結果;捕捉例外的做法依賴「MySQL 重複鍵錯誤只撤銷該敘述、交易仍可用」與例外轉譯,且要先插入 `item_id = NULL` 再 UPDATE。

**替代方案:回 `409` 處理中**。見 proposal 討論;需要先在獨立交易提交 `PROCESSING` 狀態,程序消失時要做逾時接手,複雜度不值得。

### D3. service 回傳結果物件,controller 負責 HTTP

`createUserOrder` 由 `void` 改為回傳 `(itemId, replayed)`。controller:

- 以 `@RequestHeader(value = "Idempotency-Key", required = false)` 讀取,原樣傳入 service
- 一律回 `201` 與 `{ "message": "下單成功", "itemId": ... }`;`replayed = true` 時另加回應 header `Idempotent-Replayed: true`,讓 client 與測試能分辨這是回放

key 格式驗證放在 service(可單元測試):`null` 視為未帶 key;否則必須符合 `^[A-Za-z0-9_-]{1,64}$`(UUID 可用),不符合拋 `BadRequestException` → `400`。

### D4. 清理:每小時刪除超過 24 小時的記錄

新排程類別 `IdempotencyKeyCleanupJob`(`scheduler` 套件),每小時執行:

```sql
DELETE FROM order_item_idempotency_keys
WHERE created_at < NOW() - INTERVAL 24 HOUR
LIMIT 1000
```

重複執行直到刪除筆數小於 1000。

- **時間一律用 DB 的 `NOW()`**:`created_at` 由 DB 預設值產生,比較也用 DB 時間,避免應用程式與 DB 時鐘不一致。
- **分批**:避免一次刪大量資料長時間持有鎖、拖住下單。
- **多台機器同時執行無妨**:DELETE 本身冪等,重疊時最多是某台刪到 0 筆;不需要分散式鎖。
- **24 小時**:涵蓋一般 client 的重試時間範圍(秒到分鐘級)並留足餘裕;比照常見做法(Stripe 為 24 小時)。以常數定義。
- **新增 `config/SchedulingConfig` 掛 `@EnableScheduling`**:原本唯一的 `@EnableScheduling` 在 `RedisSettlementConsumer` 上,它受 `app.scheduler.enabled` 控制,關掉自動結算會連帶停掉清理,表就會無限成長(code review 指出)。清理因此不受這個開關影響。測試直接呼叫清理方法,並驗證開關關閉時清理仍有排程。
- **實際保存 24 到 25 小時**:每小時整點才清一次,spec 的要求是「至少 24 小時」。

**替代方案:讀取時判斷過期、不刪除**。不採用:表仍會無限成長。

## Risks / Trade-offs

- **[users 鎖持有時間變長]** 驗證訂單、菜單改在持鎖期間進行。→ 接受:多出的是兩次主鍵查詢,且只影響同一使用者自己的下單排隊。
- **[有其他路徑不經 users 鎖就寫入 key]** 若未來有程式在未鎖 users 的情況下寫入同表的 key,D2 的「先查再寫」就會成為 check-then-act,同 key 可能撞唯一索引而回 409。→ 所有寫入 key 的路徑都必須先鎖 users;目前只有 `createUserOrder` 一處。
- **[超過 24 小時的重送會重複下單]** 清理後同一 key 視為新請求。→ 接受,屬保存期限的定義;已寫進 spec。
- **[同 key 不同 body 回放第一次的結果]** client 誤用 key 時會拿到與這次 body 不符的 `itemId`。→ 接受,已列為範圍外;未來可加 request hash 比對並回 `422`。
- **[回放的 `itemId` 可能已被刪除]** → 接受,回放語意即「第一次的結果」。
- **[等待時間]** 後到者會等先到者整個交易結束;最壞情況受 `innodb_lock_wait_timeout`(預設 50 秒)限制。→ 下單交易本身很短,實務上等待為毫秒級。

## Migration Plan

1. 部署含 `V10` migration 的版本;Flyway 啟動時建表,無既有資料需轉換。
2. 新表與 header 都是新增,不帶 header 的既有 client 行為不變,可直接上線,不需停機。
3. 回滾:部署前一版即可,新表留著不影響舊版;要完全移除再另寫 migration `DROP TABLE`。
