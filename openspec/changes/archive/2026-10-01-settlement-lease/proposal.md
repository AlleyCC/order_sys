# Proposal: settlement-lease

## Why

自動結算的消費端先以 `ZREM order:settlement:queue` 認領到期訂單、再呼叫 `settleOrder`。`ZREM` 成功的那一刻訂單就離開佇列,若該台機器在結算途中當掉(程序被殺、OOM、部署重啟),訂單會停在 `OPEN` 或 `CLOSED`,而 Redis 裡已經沒有它——既有的重試機制(`reEnqueue`)只接得住「拋出例外」,接不住「程序直接消失」,這筆訂單從此沒有任何機制會再碰它,金額也會一直凍結在參與者的可用餘額裡。

另一個問題是認領效率:每台機器每秒各自以 `ZRANGEBYSCORE ... LIMIT 0 10` 讀出**同一批**最早到期的十筆,再逐筆 `ZREM` 競爭,落敗的機器整輪空轉。機器越多,空轉越多,而到期訂單堆積時,第十一筆之後的訂單要等前一批全部處理完才輪得到。

## What Changes

- 認領由「讀出再逐筆 `ZREM`」改為**一次原子的「取出並搬移」**:到期訂單從佇列移到新的「處理中」集合,並記下租約到期時間(預設認領後 60 秒)。一次最多認領 10 筆;多台機器同時認領時,各自拿到的訂單互不重疊
- 結算正常結束(含餘額不足轉 `FAILED`、已被他人結算)後,從「處理中」集合移除,代表「做完了」;只有租約仍屬於自己時才生效,晚到的確認不會動到接手者
- 結算拋出例外時,沿用既有退避重試(1、2、4、8、16 秒,上限 5 次),入隊與移出「處理中」一次完成;同樣只有租約仍屬於自己時才生效
- 新增回收:定期把「處理中」集合裡租約已到期的訂單搬回佇列,讓任何一台機器重新認領。每次回收計入既有的重試次數;用完則不再搬回,改走「自動重試用完」通知(團主與管理員)
- 「自動重試用完」通知改為先記在 Redis 待通知集合,送出成功才移除,失敗下一輪再送;訂單已結算、已失敗或已取消時不發此通知
- 取消訂單、刪除全部品項時,除了從佇列移除,也從「處理中」集合移除
- **BREAKING(部署)**:結算相關的 Redis key 改為帶 hash tag 的 `{order:settlement}:*`,上線需停機並改名既有的 key(見 design.md Migration Plan)
- **行為語意改變**:同一筆訂單的結算流程由「至多執行一次」變為「至少執行一次」——租約到期時原機器可能仍在處理,另一台會接手重跑。扣款仍只會發生一次,由既有的條件式狀態寫入保證(`settleOrder` 接受 `OPEN`/`CLOSED`、扣款前搶狀態、搶輸即跳過)

**不在範圍內**

- 租約續期(處理中的機器定期延長租約)
- 改用 Redis Stream / consumer group
- Redis 本身不可用時的結算停擺
- 啟動時從 MySQL 掃描停在 `OPEN`/`CLOSED` 的過期訂單補排程(本變更上線前已遺失的訂單,需以既有的手動 `pay_order` 處理)

## Capabilities

### New Capabilities

- `settlement-claim`: 到期訂單的認領與復原——多台機器如何互斥地認領到期訂單、認領後以租約追蹤處理狀態、處理完成的確認,以及認領者消失時如何由其他機器接手

### Modified Capabilities

(無。`openspec/specs/` 目前沒有結算相關的 capability;既有的退避重試與「用完通知」行為記載於 `docs/SPEC.md` 7.3,本變更沿用、不改其規則,僅新增「租約逾期回收也計入重試次數」,於 `settlement-claim` 中定義。)

## Impact

- **程式碼**:`scheduler/RedisSettlementQueue`(新增認領、確認、回收、待通知集合操作;`reEnqueue`、`remove` 同步處理「處理中」集合;`pollDueOrders` 由認領取代)、`scheduler/RedisSettlementConsumer`(改用認領/確認,新增回收與通知)、`OrderService.notifySettlementAbandoned`(終態訂單不通知);新增四支 Lua 腳本(認領、確認、重試、回收)
- **Redis**:key 改名為 `{order:settlement}:queue`、`{order:settlement}:retries`;新增 `{order:settlement}:processing`(Sorted Set,member = orderId,score = 租約到期 epoch millis)與 `{order:settlement}:abandoned`(Set,待通知的 orderId)
- **設定**:租約長度與單次認領筆數以常數定義,預設 60 秒、10 筆
- **測試**:`RedisSettlementQueueTest` 新增認領、並行認領互斥、回收、回收計次案例;`RedisSettlementConsumerTest` 由 mock `pollDueOrders`/`remove` 改為 mock 認領/確認,既有「remove 回傳 false 即不結算」案例改寫為「未認領到的訂單不結算」
- **文件**:`docs/SPEC.md` 7.3 自動結算流程與測試表;鐵人賽 Day 22「認領成功後掛掉,任務就消失了」一節在合併後需改寫
- **不影響**:MySQL schema(無 migration)、HTTP API、WebSocket 訊息格式、`settleOrder`/`payOrder` 的扣款邏輯
