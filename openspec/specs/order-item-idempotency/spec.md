# order-item-idempotency Specification

## Purpose

讓 `POST /order/create_user_order` 在 client 重送同一次下單時不會重複寫入品項:client 以 `Idempotency-Key` 標示一次下單意圖,伺服器保存第一次成功的結果,並在重送時回傳同一個結果。

## Requirements

### Requirement: 下單回應包含品項 ID

`POST /order/create_user_order` 成功時 SHALL 回傳 `201`,body 為 `{ "message": "下單成功", "itemId": <新品項的 itemId> }`。無論是否帶 `Idempotency-Key` 皆同。

#### Scenario: 不帶 key 下單成功

- **WHEN** 使用者不帶 `Idempotency-Key` 對 OPEN 訂單下單,且可用餘額足夠
- **THEN** 回應 `201`,body 含 `message` 為「下單成功」與新寫入品項的 `itemId`

### Requirement: Idempotency-Key 為選填

`Idempotency-Key` header SHALL 為選填。未帶 header 時,系統 SHALL 依原有規則處理每一個請求,每次成功都寫入一筆新品項。

#### Scenario: 不帶 key 連送兩次

- **WHEN** 使用者不帶 `Idempotency-Key`,以相同 body 連續送出兩次下單且皆成功
- **THEN** 寫入兩筆品項,兩次回應的 `itemId` 不同

### Requirement: Idempotency-Key 格式

`Idempotency-Key` 的值 SHALL 由 1 到 64 個字元組成,字元限英文字母、數字、`-`、`_`,區分大小寫。格式不符時系統 SHALL 回 `400`,且不寫入品項。

#### Scenario: key 過長

- **WHEN** 使用者帶長度 65 的 `Idempotency-Key` 下單
- **THEN** 回應 `400`,不寫入品項

#### Scenario: key 含不允許的字元

- **WHEN** 使用者帶 `Idempotency-Key: abc def`(含空白)下單
- **THEN** 回應 `400`,不寫入品項

#### Scenario: 大小寫不同視為不同 key

- **WHEN** 使用者帶 `Idempotency-Key: abc` 下單成功後,再帶 `Idempotency-Key: ABC` 下單
- **THEN** 第二次寫入一筆新品項,`itemId` 與第一次不同

### Requirement: 重送回傳第一次的結果

同一使用者以同一 `Idempotency-Key` 第一次下單成功後,在保存期限內再次帶該 key 下單,系統 SHALL:

- 不寫入品項、不改變可用餘額、不推播餘額更新
- 不重新檢查訂單狀態、截止時間、菜單與餘額
- 回應 `201` 與第一次相同的 `itemId`,並附回應 header `Idempotent-Replayed: true`

第一次下單(非回放)的回應 SHALL NOT 帶 `Idempotent-Replayed` header。

#### Scenario: 回應遺失後重送

- **WHEN** 使用者帶 `Idempotency-Key: k1` 下單成功(`itemId` = 10),之後再以相同 key 與 body 送出
- **THEN** 第二次回應 `201`、`itemId` 為 10、header `Idempotent-Replayed: true`;該訂單中此使用者的品項只有一筆

#### Scenario: 訂單截止後重送

- **WHEN** 使用者帶 key 下單成功後,訂單已過截止時間或已非 OPEN,再以相同 key 送出
- **THEN** 回應 `201` 與第一次的 `itemId`,不回截止或狀態錯誤

#### Scenario: 品項被刪除後重送

- **WHEN** 使用者帶 key 下單成功後刪除了該品項,再以相同 key 送出
- **THEN** 回應 `201` 與第一次的 `itemId`,不寫入新品項

#### Scenario: 相同 key 但 body 不同

- **WHEN** 使用者帶 key `k1` 下單成功後,以 `k1` 送出不同的 `menuId` 或 `quantity`
- **THEN** 回應 `201` 與第一次的 `itemId`,不寫入新品項

### Requirement: 失敗的下單不保存結果

帶 `Idempotency-Key` 的下單若失敗(例如訂單不存在、非 OPEN、已截止、菜單不存在或已下架、餘額不足),系統 SHALL 不保存該 key 的結果;之後以同一 key 再次下單 SHALL 依原有規則重新處理。

#### Scenario: 餘額不足後儲值重試

- **WHEN** 使用者帶 key `k1` 下單因餘額不足回 `400`,儲值後再以 `k1` 送出
- **THEN** 第二次正常處理,寫入一筆品項並回應 `201`,不帶 `Idempotent-Replayed`

### Requirement: key 的作用範圍為每位使用者

`Idempotency-Key` SHALL 僅在同一使用者內辨識重送。不同使用者使用相同的 key SHALL 互不影響,任何使用者都 SHALL NOT 透過 key 取得他人的下單結果。

#### Scenario: 兩位使用者使用相同 key

- **WHEN** 使用者 A 帶 `Idempotency-Key: k1` 下單成功後,使用者 B 也帶 `k1` 下單
- **THEN** B 的請求正常處理並寫入 B 的品項,回應 B 的新 `itemId`,不帶 `Idempotent-Replayed`

### Requirement: 同一 key 的併發請求只寫入一次

同一使用者以同一 `Idempotency-Key` 同時送出多個下單請求時,系統 SHALL 至多寫入一筆品項。後到的請求 SHALL 等待先到者完成:先到者成功時,後到者回應 `201` 與相同 `itemId` 並帶 `Idempotent-Replayed: true`;先到者失敗時,後到者依原有規則重新處理。後到的請求 SHALL NOT 因先到者仍在處理而回 `409`。

#### Scenario: 兩個相同請求同時抵達

- **WHEN** 使用者以相同 key 與 body 同時送出兩個下單請求,且可用餘額足夠
- **THEN** 兩個請求皆回應 `201` 且 `itemId` 相同,其中恰有一個帶 `Idempotent-Replayed: true`;資料庫中只多一筆品項

### Requirement: key 記錄保存 24 小時

key 的結果 SHALL 自第一次下單成功起保存至少 24 小時。超過 24 小時的記錄 SHALL 由系統定期刪除;刪除後以同一 key 下單 SHALL 視為新的下單。

#### Scenario: 保存期限內重送

- **WHEN** 使用者帶 key 下單成功,23 小時後以同一 key 重送
- **THEN** 回應第一次的 `itemId` 並帶 `Idempotent-Replayed: true`

#### Scenario: 清理只刪除過期記錄

- **WHEN** 清理執行時,存在一筆建立於 25 小時前與一筆建立於 1 小時前的 key 記錄
- **THEN** 25 小時前的記錄被刪除,1 小時前的記錄保留

#### Scenario: 記錄過期刪除後重送

- **WHEN** 使用者帶 key 下單成功,其記錄已被清理刪除後,以同一 key 再次下單
- **THEN** 依原有規則處理,成功則寫入一筆新品項並回應新的 `itemId`
