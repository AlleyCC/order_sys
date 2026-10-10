# Design: add-recharge

## Context

動機見 proposal.md - Why;行為約定見 `specs/wallet-recharge`、`specs/transaction-audit`。

與本設計相關的現況:

- `transactions.order_id` 有 FK → `orders`,無法存放儲值單號;`type` 已是 VARCHAR,`RECHARGE` 可直接使用
- 扣款採條件式寫入:`casDebit`(`balance >= ?`)、`updateStatusIfIn`(結算搶狀態)。本設計的入帳沿用同一模式
- `BalanceChangeNotifier` 以 `@TransactionalEventListener(AFTER_COMMIT)` 推播餘額,推播失敗只記 log
- 授權:未登記於資源表的端點「登入即可存取」;V9 已將 `/admin/transactions/**` 的 **GET** 授權給 ACCOUNTANT,**POST 尚未登記**
- `@EnableScheduling` 已在 `RedisSettlementConsumer` 上開啟,全域生效
- 專案目前沒有 `Clock` bean,時間判斷直接用 `LocalDateTime.now()`

## Goals / Non-Goals

**Goals:**

- 入帳路徑只有一條,回呼、主動查單、過期關單、人工對單都只負責「問出付款結果」,再交給同一個入帳方法
- 回呼遺失、重複、偽造三種情況都能以模擬通道在測試中穩定重現
- 外部 I/O(向通道查單)不在資料庫交易內進行

**Non-Goals:**

- 通道付款頁的真實 UI(模擬通道回傳的付款資訊只是一個字串)
- 排程的分散式互斥(見 Risks)

## Decisions

### D1. 資料表 `recharge_orders`

```
recharge_orders
  recharge_id   VARCHAR(36)  PK, UUID,同時作為送給通道的商戶單號
  user_id       VARCHAR(20)  FK → users
  amount        INT          1..50000
  status        VARCHAR(20)  WAITING / SUCCESS / CLOSED
  expire_at     DATETIME     本地過期時間(建立 + 15 分鐘)
  paid_at       DATETIME     NULL,入帳時寫入
  created_at    DATETIME
  updated_at    DATETIME
  KEY (status, created_at)   主動查單:WAITING 且 created_at >= now-10min
  KEY (status, expire_at)    過期關單:WAITING 且 expire_at < now
  KEY (user_id, created_at)
```

`transactions` 新增 `recharge_id VARCHAR(36) NULL`,FK → `recharge_orders`(RESTRICT),並加 UNIQUE 索引。UNIQUE 是條件式寫入之外的第二道防線:即使入帳邏輯日後被改壞,同一筆儲值單也寫不出第二筆流水。MySQL 的 UNIQUE 允許多個 NULL,扣款紀錄不受影響。

狀態機:

```
            入帳(回呼/主動查單/過期關單/人工對單)
  WAITING ─────────────────────────────────────▶ SUCCESS
     │                                              ▲
     │ 過期關單:最後查單仍未付款                     │ 僅人工對單
     ▼                                              │
  CLOSED ───────────────────────────────────────────┘
```

狀態名稱沿用 ruoyi 的 WAITING / SUCCESS / CLOSED,存 VARCHAR(專案慣例)。

### D2. 入帳:`credit(rechargeId, allowedFrom, operator)`

同一個 `@Transactional` 內:

1. `UPDATE recharge_orders SET status='SUCCESS', paid_at=? WHERE recharge_id=? AND status IN (allowedFrom)`;影響 0 列 → 直接返回(已入帳或狀態不允許)
2. `UPDATE users SET balance = balance + ? WHERE user_id = ?`
3. 讀回新餘額,寫入 `transactions`(type=RECHARGE、recharge_id、closing_balance、created_by=operator)
4. 發布 `BalanceChangedEvent`(commit 後推播)

`allowedFrom`:自動路徑(回呼、主動查單、過期關單)只傳 `WAITING`;人工對單傳 `WAITING, CLOSED`。CLOSED → SUCCESS 只有人工能觸發,所以排程永遠不會讓已關閉的單「復活」,這個決定權留給人。

第 1 步的 UPDATE 會持有該列的列鎖直到 commit;第二個同時進來的入帳會等鎖,拿到鎖時狀態已是 SUCCESS,影響 0 列。這和結算搶狀態(`updateStatusIfIn`)是同一個機制。

`operator`:自動路徑使用儲值單的 `user_id`(和 DEBIT 用團主作為 created_by 的慣例一致);人工對單使用會計的 user_id。

**替代方案**:先 SELECT 檢查狀態再更新——檢查與更新之間有空窗,兩個回呼可以同時通過檢查。不採用。

### D3. 付款通道抽象

```java
interface PayChannel {
    void createPayment(String rechargeId, int amount, LocalDateTime expireAt);
    ChannelPayment queryPayment(String rechargeId);   // status + amount
}
enum ChannelPaymentStatus { PAID, WAITING, CLOSED, NOT_FOUND }
```

所有「問出付款結果」的路徑都經過同一個方法:

```
syncWithChannel(rechargeId, allowedFrom, operator):
  ChannelPayment p = payChannel.queryPayment(rechargeId)    ← 交易外
  if p.status == PAID && p.amount == order.amount → credit(...)
  回傳最新的儲值單狀態
```

查單在交易外執行,理由與 `BalanceChangeNotifier` 相同:外部 I/O 不該算進持鎖時間,也不該因為通道慢而拖住 DB 連線。金額不符只記 error log、不入帳(不在本次自動處理範圍,留給調整 change)。

### D4. 四個觸發來源

| 來源 | 對象 | 查到 PAID | 查到 WAITING / CLOSED / NOT_FOUND | 查單失敗 |
|---|---|---|---|---|
| 回呼 | 回呼帶的單號 | 入帳(WAITING→) | 不動,回覆成功 | 回覆 5xx 讓通道重送 |
| 主動查單(30 秒) | WAITING 且建立於 10 分鐘內 | 入帳(WAITING→) | **不動** | 記 log,下一筆照跑 |
| 過期關單(1 分鐘) | WAITING 且已過 `expire_at` | 入帳(WAITING→),不關 | 條件式關單 WAITING→CLOSED | 不關,下一輪再試 |
| 人工對單 | 指定單號 | 入帳(WAITING/CLOSED→) | 不動 | 回覆錯誤 |

主動查單「查到 NOT_FOUND 也不動」沿用 ruoyi `PayOrderServiceImpl.syncOrder` 的理由:使用者可能剛拉起付款、還沒付,這時判定未付款並關單,下一秒付款成功的回呼就入不了帳。判定「沒付」只交給過期關單,而且通道付款期限(10 分鐘)早於本地過期(15 分鐘),到關單時通道那邊已經不可能再收款。

主動查單的時間窗(10 分鐘)和通道付款期限相同:超過 10 分鐘的單通道已關閉付款入口,不會再有新付款,剩下的「付了但回呼遺失」由過期關單的最後一次查單收尾。

### D5. 建立儲值單:先寫本地,再建立通道付款

```
1. INSERT recharge_orders(WAITING)          ← 獨立 commit
2. payChannel.createPayment(...)            ← 交易外
3. 失敗 → 條件式 WAITING→CLOSED,回覆錯誤
```

**替代方案**:先建立通道付款再寫本地——若第 1 步成功、寫本地失敗,通道上就有一筆可付款、但我們這邊查無此單的付款,使用者付了錢永遠無法入帳。先寫本地則最壞情況是本地多一筆 CLOSED,不會有錢沒地方去。

### D6. 模擬通道

- `MockPayChannel implements PayChannel`:記憶體 `ConcurrentHashMap`,保存金額、狀態、付款期限;以注入的 `Clock` 判斷是否過期(過期後查詢回覆 CLOSED)
- `MockChannelController`(`POST /mock-channel/pay`,參數 `rechargeId`、`callback=ONCE|TWICE|DROP`):先把付款標記為 PAID,再依參數**呼叫回呼處理方法**零、一或兩次
- 回呼由 controller 在行程內直接呼叫,而不是發 HTTP:避免依賴自己的 host/port,測試也能同步驗證結果。`MockPayChannel` 本身不依賴儲值 service,避免 `RechargeService → PayChannel → RechargeService` 的循環依賴
- 以 `@ConditionalOnProperty(paypool.pay.channel=mock, matchIfMissing=true)` 啟用;日後接真實通道時換掉這個 bean 即可

### D7. 時間來源

新增 `Clock` bean(`Clock.systemDefaultZone()`),儲值相關程式一律用 `LocalDateTime.now(clock)`。測試替換成可前進的 Clock,就能驗證「過期」「時間窗外」而不必真的等 15 分鐘。既有程式碼不改。

### D8. 排程

`RechargeJobs` 只有兩個 `@Scheduled(fixedDelay)` 方法,各自呼叫 service 的 `syncRecentWaiting()`、`expireOverdue()` 並記錄筆數。所有邏輯在 service,測試直接呼叫 service 方法,不等排程觸發。

使用 `fixedDelay` 而非 `fixedRate`:上一輪查單若因通道緩慢超時,不會疊出下一輪。

### D9. API 與授權

| 端點 | 方法 | 授權 |
|---|---|---|
| `/user/create_recharge_order` | POST | 登入即可(未登記) |
| `/user/get_recharge_order?rechargeId=` | GET | 登入即可;非本人 → 404 |
| `/pay/notify/mock` | POST | 白名單 |
| `/mock-channel/pay` | POST | 白名單 |
| `/admin/transactions/get_all_transactions` | GET | ACCOUNTANT(V9 已授權 GET) |
| `/admin/transactions/get_recharge_orders` | GET | ACCOUNTANT(同上) |
| `/admin/transactions/sync_recharge_order` | POST | ACCOUNTANT(**V10 新登記**) |

POST 必須登記:未登記的端點預設「登入即可」,若漏登,任何使用者都能觸發人工對單,並以自己的身分寫進交易紀錄的操作者欄位。

回呼路徑帶通道名稱(`/pay/notify/mock`),日後新增通道時各自一條路徑,不必在 body 裡判斷來源。

分頁沿用 `PageLimits`(每頁上限 20)與 MyBatis-Plus `Page`。全部交易紀錄的回應新增 `AdminTransactionResponse`,既有 `TransactionResponse` 不變。

## Risks / Trade-offs

- [多台機器同時跑排程,同一筆單被重複查詢] → 重複入帳由 D2 的條件式寫入與 UNIQUE 防止;代價只是多餘的通道查詢。儲值量級下可接受
- [模擬通道的狀態存在記憶體,重啟就消失] → 重啟後 WAITING 單查詢得到 NOT_FOUND,主動查單不動,最後由過期關單關閉。模擬通道只用於開發,而且只支援單台機器
- [付款在通道期限的最後一刻完成、回呼遺失,而主動查單的時間窗剛好錯過] → 過期關單的最後一次查單會收尾,最多延遲到本地過期(15 分鐘)才入帳
- [回呼端點免登入,可能被大量偽造請求灌入] → 每個請求只造成一次查單,不會入帳;速率限制不在本次範圍
- [通道回報的金額與儲值單不符] → 不入帳,只記 error log;實際處理依賴下一個 change 的人工調整

## Migration Plan

1. `V10__add_recharge.sql`:建立 `recharge_orders`;`transactions` 新增 `recharge_id`、FK、UNIQUE;登記 `('/admin/transactions/**', 'POST')` 並授權 ACCOUNTANT;更新 ACCOUNTANT 角色說明(移除「儲值端點完成後再追加授權」)
2. 部署順序不受限制:新欄位 nullable,既有資料不用回填(種子資料的 RECHARGE 維持 `recharge_id = NULL`)
3. 回滾:移除新端點即可;資料表與欄位留著不影響舊版程式
4. RBAC 快取不需額外處理:`RbacCacheInitializer` 在啟動時清除資源與各角色的快取,V10 登記的資源於重啟後即生效
