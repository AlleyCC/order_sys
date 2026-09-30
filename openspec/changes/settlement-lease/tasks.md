# Tasks: settlement-lease

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
Queue 層用 `RedisSettlementQueueTest`(Testcontainers Redis,時間以參數傳入,不需真的等待);
Consumer 層用 `RedisSettlementConsumerTest`(Mockito)。第 1~3 組完成前,消費端仍走舊路徑,可隨時停下。

## 1. 認領(`settlement-claim`:到期訂單的互斥認領、認領後以租約追蹤)

- [x] 1.1 新增常數 `PROCESSING_KEY`、`LEASE_MS = 60_000`、`CLAIM_BATCH_SIZE = 10`(design.md D10)
- [x] 1.2 (RED)`claimDue(now)`:到期者被取出;未到期者留在 `queue`;取出者不在 `queue`、在 `processing`,score = `now + LEASE_MS`
- [x] 1.3 (RED)`claimDue(now)`:15 筆到期只取截止最早的 10 筆,其餘 5 筆留在 `queue`
- [x] 1.4 (RED)已在 `processing` 的訂單不會被 `claimDue` 取出
- [x] 1.5 (GREEN)新增 `src/main/resources/scripts/settlement-claim.lua`,以 `RedisSettlementQueue` 內的 `static final RedisScript` 欄位載入(不另建設定類別),實作 `claimDue`(design.md D1)
- [x] 1.6 (RED → GREEN)兩個執行緒同時對 20 筆到期訂單呼叫 `claimDue`(以 `CountDownLatch` 同時起跑):兩者結果無交集、聯集恰為 20 筆

## 2. 確認完成與例外重試(`settlement-claim`:結算正常結束後確認完成、結算拋出例外時退避重試)

- [x] 2.1 (RED)`ack(orderId)`:自 `processing` 移除,且 retries 中該筆被清除
- [x] 2.2 (GREEN)實作 `ack`(`ZREM processing` + `HDEL retries`,design.md D6);既有 `clearRetries` 若已無其他呼叫者則移除
- [x] 2.3 (RED)`reEnqueue`:第 1 次重試後訂單在 `queue`(score = now + 1s)、不在 `processing`
- [x] 2.4 (RED)`reEnqueue`:次數用完回傳 `false` 時,訂單不在 `queue` 也不在 `processing`
- [x] 2.5 (GREEN)`reEnqueue` 在 `ZADD queue` **之後**補 `ZREM processing`;用完時也 `ZREM processing`(design.md D5)

## 3. 租約逾期回收(`settlement-claim`:租約逾期回收)

- [x] 3.1 (RED)`reclaimExpired(now)`:租約未到期者仍在 `processing`
- [x] 3.2 (RED)`reclaimExpired(now)`:租約已到期者回到 `queue`(score = now)、不在 `processing`,重試次數 +1,回傳空集合
- [x] 3.3 (RED)`reclaimExpired(now)`:重試次數已為 5 的訂單再次逾期 → 不在 `queue` 也不在 `processing`、重試次數被清除,且出現在回傳集合中
- [x] 3.4 (GREEN)新增 `scripts/settlement-reclaim.lua`,實作 `reclaimExpired`(design.md D4);最大次數傳入 `RETRY_DELAYS_MS.length`
- [x] 3.5 (RED → GREEN)兩個執行緒同時對同一筆逾期訂單呼叫 `reclaimExpired`:`queue` 中只有一筆、重試次數只 +1

## 4. 消費端改用認領/確認/回收

- [x] 4.1 (RED)改寫 `RedisSettlementConsumerTest`:mock `claimDue` 取代 `pollDueOrders` + `remove`;`settleOrder` 成功 → 呼叫 `ack`
- [x] 4.2 (RED)`settleOrder` 拋例外 → 呼叫 `reEnqueue`、不呼叫 `ack`;`reEnqueue` 回傳 `false` → 呼叫 `notifySettlementAbandoned`
- [x] 4.3 (RED)`reclaimExpired` 回傳的每筆 orderId → 各呼叫一次 `notifySettlementAbandoned`;且 `reclaimExpired` 在 `claimDue` 之前被呼叫(`InOrder`)
- [x] 4.4 (RED)一筆 `settleOrder` 拋例外不影響同批後續訂單(沿用既有案例改寫)
- [x] 4.5 (GREEN)改寫 `RedisSettlementConsumer.pollAndSettle()`:回收 → 通知 → 認領 → 逐筆結算 → ack / reEnqueue(design.md D7)
- [x] 4.6 (REFACTOR)移除 `pollDueOrders` 及其測試案例(`addAndPollDue`、`futureOrderNotPolled` 改寫為 `claimDue` 版本或刪除)

## 5. 取消與刪除一併移出處理中(`settlement-claim`:取消訂單時一併移出處理中)

- [x] 5.1 (RED)`remove(orderId)`:訂單在 `processing` 時呼叫後,兩個集合都沒有它;回傳值仍只反映是否自 `queue` 移除
- [x] 5.2 (GREEN)`remove` 增加 `ZREM processing`(design.md D9)

## 6. 端到端情境(`settlement-claim`:認領者在結算途中消失、重複執行結算不重複扣款)

- [x] 6.1 (RED → GREEN)情境測試(Testcontainers MySQL + Redis):建立已截止訂單 → `claimDue(t)` 後**不** ack(模擬當掉)→ `reclaimExpired(t + LEASE_MS + 1)` → `claimDue` 再次取得 → `settleOrder` → 訂單 `SETTLED`、每人扣款一次
- [x] 6.2 (RED → GREEN)`OrderConcurrentSettleTest` 新增:兩個執行緒同時對同一張已截止訂單呼叫 `settleOrder`(模擬原認領者與接手者)→ 訂單 `SETTLED`、每位參與者餘額只減一次、交易紀錄每人一筆

## 7. 文件與收尾

- [x] 7.1 `docs/SPEC.md` 7.3:新增「認領與租約」段落(兩個 key、租約 60 秒、回收計入重試次數、至少一次語意);測試表補上第 1~6 組的案例
- [x] 7.2 `docs/SPEC.md` 記錄已知缺口:放棄通知送出前當掉、放棄時訂單可能停在 `OPEN`(design.md Risks)
- [x] 7.3 全部測試通過(`./mvnw test`);`openspec validate settlement-lease --strict` 通過

## 8. Code review 修正(design.md D4、D5、D6、D7、D10、D11)

- [x] 8.1 (RED → GREEN)`ack(orderId, leaseUntil)` 改為 `settlement-ack.lua`:租約不是自己的就不動作(修:晚到的 ack 刪掉接手者的租約)
- [x] 8.2 (RED → GREEN)`reEnqueue(orderId, leaseUntil)` 改為 `settlement-retry.lua`:驗證租約,放回 queue 與移出 processing 一次完成,用完進待通知集合
- [x] 8.3 (RED → GREEN)回收與重試用完的訂單進 `{order:settlement}:abandoned`;消費端每輪結尾逐筆 `claimAbandoned` 後通知,失敗 `markAbandoned` 放回(修:通知失敗訂單無聲遺失、通知失敗卡住同批訂單)
- [x] 8.4 (RED → GREEN)消費端:`settleOrder` 單獨 try,ack / reEnqueue 失敗只記 log、不中斷同批(修:ack 失敗被當成結算失敗)
- [x] 8.5 (RED → GREEN)`notifySettlementAbandoned`:訂單已是 SETTLED / FAILED / CANCELLED 時不通知
- [x] 8.6 回收與認領各自重新取時間;回收上限獨立為 `RECLAIM_BATCH_SIZE = 100`;腳本載入抽成 `listScript`;`remove` 改用 pipeline
- [x] 8.7 key 改為 `{order:settlement}:*` hash tag;design.md Migration Plan 改為停機切換並附改名指令
- [x] 8.8 `docs/SPEC.md` 同步 key 名稱、租約驗證、待通知集合與測試表
- [x] 8.9 全部測試通過(`./mvnw test`);`openspec validate settlement-lease --strict` 通過
