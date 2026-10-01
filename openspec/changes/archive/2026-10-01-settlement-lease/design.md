# Design: settlement-lease

## Context

動機見 proposal.md「Why」,行為要求見 `specs/settlement-claim/spec.md`。這裡只列會影響做法的現況:

- 變更前,`RedisSettlementQueue` 以 Sorted Set `order:settlement:queue`(member = orderId,score = 到期 epoch millis)排程;重試次數記在 Hash `order:settlement:retries`,退避間隔 `RETRY_DELAYS_MS = {1s, 2s, 4s, 8s, 16s}`
- 變更前,`RedisSettlementConsumer.pollAndSettle()` 每秒執行:`pollDueOrders`(`ZRANGEBYSCORE ... LIMIT 0 10`)→ 逐筆 `remove`(`ZREM`)→ `settleOrder` → 成功 `clearRetries`;例外則 `reEnqueue`,用完呼叫 `orderService.notifySettlementAbandoned`
- `settleOrder` 已可重入:接受 `OPEN`/`CLOSED`、其餘狀態直接返回;`OPEN → CLOSED` 與扣款前搶狀態都是條件式寫入,搶輸時正常返回(不拋例外);餘額不足在 `settleOrder` 內處理、不外拋。注意:**「正常返回」不代表訂單已到終態**——`OPEN → CLOSED` 搶輸時,訂單可能正由另一個實例處理、狀態為 `CLOSED`(見 D6)
- `OrderService` 另有兩處呼叫 `settlementQueue.remove`:`cancelOrder` 與「刪除全部品項」,都在條件式寫入 `CANCELLED` 成功之後
- Redis 7 單機(docker-compose 與 Testcontainers 皆為 `redis:7-alpine`),測試環境 `app.scheduler.enabled=false`,消費端不會在整合測試中自動執行

## Goals / Non-Goals

**Goals:**

- 認領、確認完成、例外重試、回收都是單一原子動作,認領者在任何時間點消失都不會讓訂單同時不在 queue 與 processing
- 晚到的確認或重試(租約已被他人接手)不影響接手者
- 重試用完的通知失敗時不遺失,下一輪再送
- 不改 `settleOrder` / `payOrder` 的扣款邏輯,冪等性沿用既有的條件式寫入

**Non-Goals:**

- 以 Redis 伺服器時間取代 JVM 時間(理由見 D8)
- 外部化設定(租約長度、批次大小維持程式常數,與 `RETRY_DELAYS_MS` 一致)
- 租約續期(heartbeat)

## Decisions

### D1. 認領用 Lua 腳本,在 Redis 內完成「查詢 + 搬移」

`scripts/settlement-claim.lua`:

```lua
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
for _, id in ipairs(ids) do
  redis.call('ZREM', KEYS[1], id)
  redis.call('ZADD', KEYS[2], ARGV[3], id)
end
return ids
```

腳本執行期間 Redis 不處理其他命令,所以兩個實例的認領必然一先一後,後者看到的佇列已經少了前者搬走的訂單——這同時解決「搶同一批而空轉」。

`claimDue` 回傳 `ClaimedBatch(orderIds, leaseUntil)`:`leaseUntil` 是這批寫進 processing 的 score,之後確認或重試時用來證明「租約還是我的」(D6)。

**替代方案:**
- 保留 `ZRANGEBYSCORE` + 逐筆 `ZREM`,成功後再 `ZADD processing`:`ZREM` 與 `ZADD` 之間當掉,訂單兩邊都不在,正是要補的缺口
- `WATCH` + `MULTI`:多實例競爭同一個 key 時交易頻繁失敗重試,且仍需兩次來回
- 逐筆 Lua(一次搬一筆):正確但每筆一次來回,批次腳本已足夠短

### D2. 「處理中」用 Sorted Set,score 存租約到期時間

score 直接是到期時間,回收時一個 `ZRANGEBYSCORE processing -inf now` 就拿到所有逾期者,與 `queue` 的查詢方式對稱。score 同時是租約的身分證明(D6)。

**替代方案:** Hash 存認領時間——回收時要取出全部再逐筆比較,且無法在腳本內用範圍查詢限量處理。

### D3. 不改用 Redis Stream

Stream 的 consumer group + `XAUTOCLAIM` 能提供「未確認即可接手」,但沒有「到指定時間才可取出」的能力。仍需保留 Sorted Set 管截止時間,再加一個搬運步驟把到期者寫入 Stream,結構從一個變兩個且多一段需要保證原子性的轉送。

### D4. 回收用 Lua 腳本,計入重試次數;用完的進待通知集合

`scripts/settlement-reclaim.lua`:逐筆取出 processing 中 score ≤ now 的訂單(一次最多 `RECLAIM_BATCH_SIZE = 100` 筆),`HINCRBY retries`,移出 processing;未超過上限則以 score = now 放回 queue,超過則 `HDEL retries` 並 `SADD abandoned`。

- 放回 `queue` 的 score 用 `now`,下一次認領即可取得;租約本身已是 60 秒的延遲,不再疊加退避
- **為什麼要計次**:若某張訂單的結算會讓 JVM 當掉(毒藥訊息),不計次會無限循環「認領 → 當掉 → 回收」。與例外重試共用 retries 與上限 5,語意是「這張訂單總共失敗過幾次」
- **為什麼放進集合而不是直接回傳給 Java 通知**:回傳後才通知,中間任何失敗(通知時 DB 查詢失敗、程序當掉)都會讓已經移出兩個集合的訂單無聲消失;而重試會用完,通常正是因為 DB 出問題,通知本身也最可能在這時失敗(D11)
- 回收上限與認領上限分開:回收只動 Redis,可以一次處理較多;調小認領批次時不應連帶拖慢回收

### D5. 例外重試用 Lua 腳本,並驗證租約

`scripts/settlement-retry.lua`:先比對 processing 中該訂單的 score 是否等於傳入的 `leaseUntil`,不等就回傳 -1、不做任何事;相等則 `HINCRBY retries`、移出 processing,未超過上限就依第 n 次的間隔放回 queue,超過則 `HDEL retries` 並 `SADD abandoned`。間隔陣列由 Java 以 ARGV 傳入,個數即上限。

放回 queue 與移出 processing 在同一支腳本內,不再有「兩個命令之間當掉」的窗口,也就不需要依賴命令順序。

**為什麼要驗證租約**:租約逾期後別的實例可能已重新認領,原認領者晚到的重試若照做,會刪掉接手者的 processing 紀錄、多計一次重試,甚至在次數用完時對一張仍在處理中的訂單發出放棄通知。

### D6. 確認完成(ack)驗證租約擁有者

`scripts/settlement-ack.lua`:processing 中的 score 等於傳入的 `leaseUntil` 才 `ZREM processing` + `HDEL retries`,否則不做任何事。

**為什麼不能省略**(code review 修正,原設計認為可省略):`settleOrder` 在 `OPEN → CLOSED` 條件式寫入搶輸時會正常返回,此時訂單不是終態,而是正由接手者處理:

```text
時間   實例 A(原認領者,租約已逾期)      實例 B(接手者)                   processing
────────────────────────────────────────────────────────────────────────────────────
t1                                        claimDue → 寫入新租約            {ord: B 的租約}
t2     讀到 OPEN                          讀到 OPEN
t3                                        OPEN → CLOSED 成功
t4     OPEN → CLOSED 搶輸,正常返回
t5     ack(不驗證)→ 刪掉 B 的紀錄                                         {}
t6                                        扣款途中當掉
```

t6 之後訂單停在 `CLOSED`,不在 queue 也不在 processing,永遠不會被回收。驗證租約後,t5 的 ack 發現 score 是 B 的租約而不動它;B 當掉後,租約到期即被回收。

租約時間以毫秒為單位,由各實例在認領當下以 `now + LEASE_MS` 計算;接手必然發生在原租約到期之後,新租約的值必然不同,不會誤判。

### D7. 每輪順序:回收 → 認領 → 逐筆結算與釋放 → 通知

- 回收在 `processing` 為空時只是一次空的範圍查詢,成本低;放在認領之前,逾期訂單最晚在租約到期後 1 秒被放回
- 回收與認領各自重新取 `System.currentTimeMillis()`,避免前段耗時讓認領的租約起點落後、實際租約被縮短
- **確認與結算分開處理**:`settleOrder` 放在自己的 try 內,只有它拋例外才 `reEnqueue`;`ack` / `reEnqueue` 本身失敗(Redis 短暫錯誤)只記 log,訂單仍在 processing,租約到期後被回收重跑。若 `ack` 失敗被當成結算失敗,會對已結算的訂單消耗重試次數,最後發出錯誤的放棄通知
- 每筆訂單的釋放失敗都被接住,不會中斷同批其餘訂單;否則其餘已認領的訂單要等 60 秒租約到期才被回收,且各被多計一次重試
- 通知放在最後,不拖延本輪認領

**替代方案:** 回收獨立成 `@Scheduled(fixedRate = 10000)`——每秒省一次 Redis 呼叫,但多一個排程點與測試入口,對「已經晚了 60 秒」的情境無實質差別。

### D8. 時間由 Java 傳入腳本,不用 Redis `TIME`

`now`、`leaseUntil` 由 `System.currentTimeMillis()` 計算後以 ARGV 傳入。

- 與 `queue` 的 score(`add` 以 JVM 時間計算)保持同一個時間基準
- 整合測試可傳入固定時間,直接驗證「未到期不回收 / 到期才回收」,不必真的等 60 秒

代價見 Risks 第一項。

### D9. 取消與刪除全部品項時,一併移出 `processing`

`remove(orderId)` 以 pipeline 對 processing 與 queue 各送一次 `ZREM`(一次來回),回傳值維持「是否從 queue 移除」。呼叫點都在 `CANCELLED` 條件式寫入成功之後,此時訂單已是終態:

- 若某實例正在結算它,條件式搶狀態會失敗、`settleOrder` 正常返回、ack 發現 processing 中已無此訂單而不動作
- 若認領者已消失,不移出的話會在租約到期後被回收一次、白計一次重試

### D10. key 帶 hash tag

四個 key 都以 `{order:settlement}` 為前綴。Lua 腳本一次操作多個 key,在 Redis Cluster 中必須落在同一個 slot,否則回傳 `CROSSSLOT` 錯誤;目前是單機,但 hash tag 讓日後換成 cluster 時不必再搬一次資料。代價是本次上線要改名既有的 key(見 Migration Plan)。

### D11. 重試用完的通知走待通知集合(Set)

| 步驟 | 動作 |
|---|---|
| 放棄時 | 腳本(D4、D5)`SADD {order:settlement}:abandoned` |
| 每輪結尾 | `SMEMBERS` 取出清單,逐筆 `SREM`;回傳 1 的實例才通知,多實例不會重複通知 |
| 通知失敗 | `SADD` 放回,下一輪再試 |

`notifySettlementAbandoned` 另外檢查訂單狀態:只有仍停在 `OPEN` / `CLOSED` 的才通知。結算成功後、ack 前機器當掉,訂單會被回收並計次;若次數因此用完,訂單其實已是 `SETTLED`,不應告訴團主「系統結算失敗」。

### D12. 常數、腳本與移除的 API

| 常數 | 值 |
|---|---|
| `KEY` / `PROCESSING_KEY` / `RETRY_KEY` / `ABANDONED_KEY` | `{order:settlement}:queue` / `:processing` / `:retries` / `:abandoned` |
| `LEASE_MS` | `60_000` |
| `CLAIM_BATCH_SIZE` | `10` |
| `RECLAIM_BATCH_SIZE` | `100` |

四支腳本放在 `src/main/resources/scripts/`(claim、ack、retry、reclaim),以 `RedisScript.of` 載入成 `static final` 欄位;Spring Data Redis 以 `EVALSHA` 執行並在腳本快取遺失時自動改用 `EVAL`。

`pollDueOrders`、`clearRetries` 移除;`reEnqueue` 改為 `reEnqueue(orderId, leaseUntil)` 且不再回傳是否用完(放棄改由待通知集合處理);`ack` 改為 `ack(orderId, leaseUntil)`。

## Risks / Trade-offs

- **[各實例 JVM 時鐘不一致]** 時鐘超前的實例會提早回收別人的租約 → 造成重複執行,由 `settleOrder` 冪等擋住,不會重複扣款;租約 60 秒遠大於一般 NTP 誤差
- **[一批處理時間超過租約]** 10 筆 × 每筆超過 6 秒時,後段訂單在輪到之前租約就到期,被別的實例接手 → 同上,只是白工;原認領者的 ack / 重試因租約驗證而不動作。若實測結算明顯變慢,優先調小 `CLAIM_BATCH_SIZE`
- **[通知在 SREM 之後、送出之前當掉]** 該筆已移出待通知集合,通知未送出 → 訂單停在 `OPEN`/`CLOSED` 且無人知情。窗口只有一次通知的時間,比原本「回收腳本回傳到通知送完」小得多;記錄於 SPEC 已知缺口
- **[放棄時訂單仍為 `OPEN`]** 若結算在寫入 `CLOSED` 之前就持續失敗,放棄後訂單停在 `OPEN`,截止後仍可能被下單 → 既有行為,本變更不改變;記錄於 SPEC 已知缺口
- **[通知持續失敗]** 待通知集合中的訂單每秒重試一次通知,DB 長時間不可用時每秒一筆 error log → 可接受;DB 恢復後自動送出
- **[語意變為至少一次]** 同一張訂單的結算流程可能跑兩次 → 扣款由條件式搶狀態保證只發生一次;結算成功通知也只會發一次,因為搶狀態失敗的一方走 `ConflictException` 分支,只記 log、不發通知。例外:第一次以餘額不足轉 `FAILED`、另一方恰好已讀到 `CLOSED` 時,會再嘗試扣款一次(仍不足,不扣錢),參與者可能收到兩則「結算失敗」通知

## Migration Plan

1. **部署需停機切換**:key 改名(D10)後新舊版本讀寫不同的 key,不能滾動部署並存。步驟:
   1. 停止所有舊版實例
   2. 改名既有的 key(不存在的 key 會回 `ERR no such key`,可忽略):

      ```bash
      docker exec paypool-redis redis-cli RENAME order:settlement:queue "{order:settlement}:queue"
      ```

      ```bash
      docker exec paypool-redis redis-cli RENAME order:settlement:retries "{order:settlement}:retries"
      ```

   3. 啟動新版
2. **上線前已遺失的訂單**:本變更不處理。上線後可查詢截止時間已過、狀態仍為 `OPEN`/`CLOSED` 的訂單,由管理員以 `pay_order` 手動結算
3. **回滾**:先停止所有新版實例,把 processing 中的訂單搬回佇列(score 0 代表立即到期)、查看待通知集合交由管理員處理,再把 key 改回舊名:

   ```bash
   docker exec paypool-redis redis-cli EVAL "local ids=redis.call('ZRANGE',KEYS[2],0,-1) for _,id in ipairs(ids) do redis.call('ZADD',KEYS[1],ARGV[1],id) end redis.call('DEL',KEYS[2]) return #ids" 2 "{order:settlement}:queue" "{order:settlement}:processing" 0
   ```

   ```bash
   docker exec paypool-redis redis-cli SMEMBERS "{order:settlement}:abandoned"
   ```

   ```bash
   docker exec paypool-redis redis-cli RENAME "{order:settlement}:queue" order:settlement:queue
   ```

   ```bash
   docker exec paypool-redis redis-cli RENAME "{order:settlement}:retries" order:settlement:retries
   ```

## Open Questions

- 60 秒租約與 10 筆批次是否合適,需上線後依結算實際耗時(log 中「starting settlement」到結果通知的間隔)確認;調整只改常數,不影響規格與任務拆分
