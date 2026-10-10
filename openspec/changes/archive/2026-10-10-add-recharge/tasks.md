# Tasks: add-recharge

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
Controller 用 `@SpringBootTest` + `MockMvc` + Testcontainers;判斷邏輯用 Mockito;條件式寫入用 Mapper 整合測試。
時間一律經由注入的 `Clock`(design.md D7),測試以可前進的 Clock 驗證過期與時間窗,不真的等待。

## 1. Schema 與基礎

- [x] 1.1 新增 `V10__add_recharge.sql`:`recharge_orders`(含三個索引)、`transactions.recharge_id`(FK + UNIQUE)、登記 `('/admin/transactions/**', 'POST')` 並授權 ACCOUNTANT、更新 ACCOUNTANT 角色說明(design.md D1、Migration Plan)
- [x] 1.2 `./mvnw flyway:migrate` 驗證,並以 SQL 確認資料表、FK、UNIQUE、資源與授權都存在
- [x] 1.3 新增 `RechargeStatus` enum(WAITING / SUCCESS / CLOSED)、`RechargeOrder` entity、`RechargeOrderMapper`;`Transaction` entity 新增 `rechargeId`
- [x] 1.4 新增 `Clock` bean(design.md D7)

## 2. 條件式寫入(`wallet-recharge`:每筆儲值單至多入帳一次)

- [x] 2.1 (RED)Mapper 測試 `updateStatusIfIn`:WAITING → SUCCESS 影響 1 列並寫入 `paid_at`;對已 SUCCESS 的單再做一次影響 0 列
- [x] 2.2 (RED)Mapper 測試:CLOSED 的單在 `allowedFrom = [WAITING]` 時影響 0 列,在 `[WAITING, CLOSED]` 時影響 1 列
- [x] 2.3 (RED)Mapper 測試 `creditBalance`:餘額增加指定金額
- [x] 2.4 (GREEN)`RechargeOrderMapper.xml` 實作 `updateStatusIfIn`;`UserMapper.xml` 新增 `creditBalance`
- [x] 2.5 (RED)直接以 SQL 對同一 `recharge_id` 寫入兩筆 transactions → 第二筆違反 UNIQUE

## 3. 付款通道與模擬通道(`wallet-recharge`:模擬付款通道可重現回呼異常)

- [x] 3.1 新增 `PayChannel` 介面、`ChannelPayment`、`ChannelPaymentStatus`(design.md D3)
- [x] 3.2 (RED)`MockPayChannelTest`:建立付款後查詢為 WAITING;未建立的單查詢為 NOT_FOUND
- [x] 3.3 (RED)標記付款後查詢為 PAID 且金額正確
- [x] 3.4 (RED)Clock 前進超過付款期限 → 查詢為 CLOSED,且標記付款失敗
- [x] 3.5 (GREEN)實作 `MockPayChannel`(`ConcurrentHashMap` + Clock,`@ConditionalOnProperty`,design.md D6)

## 4. 入帳與查單(`wallet-recharge`:入帳、回呼、主動查單、過期關單、人工對單)

- [x] 4.1 (RED)整合測試 `credit`:餘額增加、交易紀錄一筆(RECHARGE、`recharge_id`、`closing_balance`、`created_by`)、儲值單 SUCCESS
- [x] 4.2 (RED)整合測試:同一筆儲值單呼叫 `credit` 兩次 → 餘額只加一次、流水只有一筆
- [x] 4.3 (RED)並行測試:兩個執行緒以 `CountDownLatch` 同時對同一筆單 `credit` → 餘額只加一次、流水只有一筆
- [x] 4.4 (RED)`credit` commit 後發布 `BalanceChangedEvent`;第 2 步之後拋例外 → 儲值單、餘額、流水全部回滾
- [x] 4.5 (GREEN)實作 `RechargeService.credit(rechargeId, allowedFrom, operator)`(design.md D2)
- [x] 4.6 (RED)Mockito:`syncWithChannel` 查到 PAID 且金額相符 → 呼叫 `credit`;金額不符、WAITING、CLOSED、NOT_FOUND → 不呼叫
- [x] 4.7 (GREEN)實作 `syncWithChannel`,查單在交易外執行(design.md D3)
- [x] 4.8 (RED)`syncRecentWaiting`:只處理 WAITING 且建立於 10 分鐘內的單;單筆查單拋例外不影響其他筆
- [x] 4.9 (RED)`syncRecentWaiting`:通道回覆 NOT_FOUND / CLOSED → 儲值單仍為 WAITING
- [x] 4.10 (GREEN)實作 `syncRecentWaiting`
- [x] 4.11 (RED)`expireOverdue`:已過期 + 通道 PAID → SUCCESS(不關單);已過期 + 通道 WAITING / CLOSED / NOT_FOUND → CLOSED;查單拋例外 → 仍為 WAITING
- [x] 4.12 (RED)`expireOverdue`:未過期的 WAITING 單不受影響
- [x] 4.13 (RED)並行測試:過期關單與入帳同時處理同一筆單 → 最終只會是 SUCCESS(餘額加一次)或 CLOSED(餘額不變)其中之一
- [x] 4.14 (GREEN)實作 `expireOverdue`(關單以條件式 WAITING → CLOSED)
- [x] 4.15 新增 `RechargeJobs`(`@Scheduled(fixedDelay)` 30 秒 / 60 秒),只呼叫 service 並記錄筆數(design.md D8)

## 5. 使用者端點(`wallet-recharge`:建立儲值單、查詢自己的儲值單)

- [x] 5.1 (RED)建立儲值單:金額 500 → 200,回覆單號 / WAITING / 過期時間;DB 有 WAITING 單,通道有對應付款,且通道期限早於本地過期
- [x] 5.2 (RED)金額 0、負數、50001 → 400,不建立儲值單
- [x] 5.3 (RED)通道建立付款失敗 → 回覆錯誤,儲值單為 CLOSED(design.md D5)
- [x] 5.4 (RED)查詢自己的儲值單 → 200;查詢他人的單 → 404;不存在 → 404
- [x] 5.5 (GREEN)實作 `RechargeController` 與 service 方法

## 6. 回呼與模擬付款端點(`wallet-recharge`:回呼只作為查單的觸發)

- [x] 6.1 (RED)未登入呼叫回呼端點不被 401 擋下;未登入呼叫模擬付款端點不被擋下
- [x] 6.2 (RED)模擬付款 `ONCE` → 儲值單 SUCCESS、餘額 + 金額
- [x] 6.3 (RED)模擬付款 `TWICE` → 餘額只加一次、流水一筆
- [x] 6.4 (RED)模擬付款 `DROP` → 儲值單仍為 WAITING;接著呼叫 `syncRecentWaiting` → SUCCESS(回呼遺失由主動查單補上)
- [x] 6.5 (RED)對尚未付款的單直接呼叫回呼端點(偽造) → 回覆成功,儲值單仍為 WAITING、餘額不變
- [x] 6.6 (RED)查單拋例外時回呼回覆 5xx;查無此單時回覆成功
- [x] 6.7 (GREEN)實作 `PayNotifyController`(`/pay/notify/mock`)、`MockChannelController`(`/mock-channel/pay`),兩者加入 `SecurityConstants.WHITE_LIST`

## 7. 會計端點(`transaction-audit`)

- [x] 7.1 (RED)`get_all_transactions`:不帶篩選 → 全體使用者第一頁 + 總筆數,由新到舊;依 type / userId / 日期區間篩選正確;DEBIT 金額為負、RECHARGE 為正並帶 `rechargeId`
- [x] 7.2 (RED)`get_all_transactions`:size 超過上限、page < 1 → 400
- [x] 7.3 (RED)`get_recharge_orders`:依狀態 / userId / 日期區間篩選正確
- [x] 7.4 (RED)`sync_recharge_order`:CLOSED 且通道 PAID → SUCCESS、流水 `created_by` 為會計;通道未付款 → 狀態不變;已 SUCCESS → 不變並回覆 SUCCESS
- [x] 7.5 (RED)授權:一般使用者、客服呼叫三個端點 → 403,且對單不異動資料;會計 → 200
- [x] 7.6 (GREEN)`AdminQueryController` 新增三個端點、`AdminTransactionResponse`、`RechargeOrderResponse`(design.md D9)

## 8. 文件與收尾

- [x] 8.1 `docs/SPEC.md`:新增儲值流程與狀態機、API、`recharge_orders` 資料表、`transactions.recharge_id`、測試表;更新 ACCOUNTANT 授權說明
- [x] 8.2 跑完整測試套件,確認既有測試(`UserControllerTest`、`AdminQueryControllerTest`、RBAC 系列)不受影響
- [x] 8.3 本機啟動,走一次「建立 → 模擬付款 DROP → 等待主動查單入帳」與「建立 → 不付款 → 過期關單」,以 SQL 確認結果
