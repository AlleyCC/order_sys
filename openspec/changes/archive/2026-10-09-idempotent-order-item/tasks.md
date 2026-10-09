# Tasks: idempotent-order-item

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
HTTP 行為用 `OrderControllerTest`(`@SpringBootTest` + `MockMvc` + Testcontainers);
併發用 `OrderConcurrentFreezeTest` 的寫法(`CountDownLatch` 同時起跑);清理用 Testcontainers MySQL 直接驗證資料列。

## 1. Schema

- [x] 1.1 新增 `V10__add_order_item_idempotency_keys.sql`:建立 `order_item_idempotency_keys`(欄位、`ascii_bin`、`UNIQUE (user_id, idempotency_key)`、`INDEX (created_at)`、`user_id` FK `ON DELETE CASCADE`、`item_id` 不設 FK,design.md D1)
- [x] 1.2 確認索引與 collation(dev DB 的 V9 checksum 與本分支不符,改以 Testcontainers 驗證:migration 全跑通過,唯一索引由回放測試、`ascii_bin` 由大小寫測試證實)
- [x] 1.3 新增 entity `OrderItemIdempotencyKey` 與 mapper `OrderItemIdempotencyKeyMapper`

## 2. 回應帶 itemId(`下單回應包含品項 ID`)

- [x] 2.1 (RED)`OrderControllerTest`:不帶 key 下單成功,回應 `201` 且 body 含 `itemId`,值等於 DB 新寫入品項的 `item_id`
- [x] 2.2 (GREEN)`createUserOrder` 改為回傳結果物件 `(itemId, replayed)`;controller 回傳 `{ message, itemId }`(design.md D3)
- [x] 2.3 調整 `OrderServiceTest` 中因回傳值改變而失敗的既有案例(回傳值改變未造成失敗;第 4 組加參數後補上 `null`)
- [x] 2.4 (RED → GREEN)不帶 key、相同 body 連送兩次:寫入兩筆、`itemId` 不同、皆無 `Idempotent-Replayed`

## 3. key 格式驗證(`Idempotency-Key 格式`)

- [x] 3.1 (RED)key 長度 65 → `400`,不寫入品項;key 含空白 → `400`
- [x] 3.2 (RED)key 長度 64、UUID 格式 → 正常下單
- [x] 3.3 (GREEN)controller 以 `@RequestHeader(required = false)` 讀取;service 驗證 `^[A-Za-z0-9_-]{1,64}$`,不符拋 `BadRequestException`

## 4. 保存與回放(`重送回傳第一次的結果`、`key 的作用範圍為每位使用者`)

- [x] 4.1 (RED)帶 key `k1` 下單兩次:第二次 `201`、同一 `itemId`、header `Idempotent-Replayed: true`;第一次無此 header;DB 只有一筆品項
- [x] 4.2 (RED)帶 key 下單成功後把訂單改為過截止時間,再以同 key 送出 → `201` 與第一次的 `itemId`
- [x] 4.3 (RED)帶 key 下單成功後刪除該品項,再以同 key 送出 → `201` 與原 `itemId`,不寫入新品項
- [x] 4.4 (RED)`k1` 成功後以 `k1` 送出不同 `menuId` → 回放第一次的 `itemId`
- [x] 4.5 (RED)`abc` 成功後送 `ABC` → 寫入新品項
- [x] 4.6 (RED)使用者 A 用 `k1` 成功後,使用者 B 用 `k1` → B 正常寫入、無 `Idempotent-Replayed`
- [x] 4.7 (GREEN)service 交易第一步 `INSERT` key(`item_id = NULL`);寫入品項後 `UPDATE item_id`;捕捉 `DuplicateKeyException` 後 `SELECT item_id` 回傳 `replayed = true`,不發布 `BalanceChangedEvent`(design.md D2)
- [x] 4.8 (GREEN)controller 在 `replayed = true` 時加上 `Idempotent-Replayed: true`

## 5. 失敗不保存(`失敗的下單不保存結果`)

- [x] 5.1 (RED)帶 `k1` 下單因餘額不足回 `400`;DB 無 `k1` 記錄
- [x] 5.2 (RED)接著儲值,再以 `k1` 送出 → 正常寫入、`201`、無 `Idempotent-Replayed`
- [x] 5.3 (RED)帶 key 對不存在的訂單下單 → `404`,DB 無該 key 記錄
- [x] 5.4 確認以上在第 4 組實作下即通過(交易回滾帶走 key);若未通過再修正

## 6. 併發(`同一 key 的併發請求只寫入一次`)

- [x] 6.1 (RED → GREEN)同一使用者以相同 key 與 body,兩個執行緒同時下單:皆成功、`itemId` 相同、恰一個 `replayed = true`、DB 只多一筆品項(首次執行揭露 FK 造成的兩方死鎖,依 design.md D2 改為先鎖 users;以 40 輪驗證後恢復 5 輪)
- [x] 6.2 (RED → GREEN)先到者結束後後到者的行為:測試自己開交易扮演先到者(鎖 users、插入同一 key、不 commit),確認被測請求卡在 users 鎖後,rollback → 被測請求重新執行且 `replayed = false`;commit → 被測請求回放先到者的 `itemId`、不寫入
- [x] 6.3 確認不帶 key 的既有併發測試(`OrderConcurrentFreezeTest`)仍通過

## 7. 清理(`key 記錄保存 24 小時`)

- [x] 7.1 (RED)插入一筆 `created_at` 為 25 小時前、一筆為 1 小時前的記錄,執行清理 → 前者刪除、後者保留
- [x] 7.2 (RED)過期筆數超過一批(1000):執行一次清理後全部刪除
- [x] 7.3 (GREEN)新增 `scheduler/IdempotencyKeyCleanupJob`:每小時 `DELETE ... WHERE created_at < NOW() - INTERVAL 24 HOUR LIMIT 1000`,重複至刪除筆數小於批次大小;保存期限與批次大小以常數定義(design.md D4)
- [x] 7.4 (RED → GREEN)記錄被清理後以同 key 再下單 → 寫入新品項、新 `itemId`

## 8. 文件與收尾

- [x] 8.1 `docs/SPEC.md` `POST /order/create_user_order`:Request 表加 `Idempotency-Key`、Response 加 `itemId` 與 `Idempotent-Replayed`、業務邏輯補冪等步驟
- [x] 8.2 `docs/SPEC.md` DB schema 章節新增 `order_item_idempotency_keys`
- [x] 8.3 `./mvnw test` 全部通過
- [x] 8.4 `openspec validate idempotent-order-item --strict`

## 9. Code review 修正

- [x] 9.1 (RED → GREEN)清理排程不再依附自動結算的開關:新增 `config/SchedulingConfig`(`@EnableScheduling`);測試在 `app.scheduler.enabled=false` 下驗證 `purgeExpired` 仍有排程
- [x] 9.2 (REFACTOR)users 鎖之後改為「先查 key、品項寫入後才一次寫入 key」,移除捕捉 `DuplicateKeyException` 與 `item_id = NULL` 再 UPDATE(design.md D2)
- [x] 9.3 `docs/SPEC.md`、design.md 同步:保存「至少」24 小時、先查再寫、`SchedulingConfig`
- [x] 9.4 `./mvnw test` 全部通過
