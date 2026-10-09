# Proposal: idempotent-order-item

## Why

`POST /order/create_user_order` 沒有任何防重複機制。請求已送達、回應卻在路上遺失(逾時、斷線、切換網路)時,client 無從得知是否成功,只能重送;伺服器把重送當成新的下單,多寫一筆 `order_items`,使用者若沒發現,結算時就被扣兩次款。前端停用按鈕只擋得住「手指連點」,擋不住這種重送,只有伺服器能分辨「這是同一次下單」。

常見的「方法 + 參數」防重複鎖(例如 ruoyi `@Idempotent`)不適用:規格明定同一使用者可重複點相同品項(`docs/SPEC.md` `POST /order/create_user_order` 業務邏輯第 8 點),相同參數的第二份會被誤擋;鎖的過期時間短於處理時間時重送仍會穿過;且重送拿到的是錯誤而非第一次的結果——那是防重複,不是冪等。

## What Changes

- `create_user_order` 接受選填的 `Idempotency-Key` header:由 client 為「一次下單意圖」產生(建議 UUID),重送時沿用同一個值,再次下單時換新值
- 帶 key 的請求,伺服器以 `(使用者, key)` 為單位記錄第一次**成功**的結果;之後同一使用者帶同一 key 的請求不再寫入品項、不再檢查訂單狀態與餘額,直接回傳第一次的結果(`201` + 同一個 `itemId`)
- key 的記錄與品項寫入在同一個交易中:下單失敗(訂單不存在、已截止、餘額不足等)時一併撤銷,不留下記錄,client 用同一 key 重試會重新執行
- 同一 key 的併發請求:後到的請求等待先到者完成,先到者成功則回傳其結果,失敗則由後到者重新執行;不回 `409`
- 不同使用者使用相同的 key 互不影響
- 未帶 key 時行為與現在完全相同
- key 格式不合法(空白、超過長度上限)時回 `400`
- 回應新增 `itemId`:`{ "message": "下單成功", "itemId": 123 }`(新增欄位,不影響既有 client)
- key 記錄保存 24 小時,由定期工作刪除過期記錄;超過保存期限後再用同一 key,視為新的下單

**不在範圍內**

- 同一 key 但 request body 不同時回 `422`(目前以第一次的結果為準,不比對 body)
- `create_order`、`pay_order` 等其他寫入端點的冪等
- 把 header 改為必填

## Capabilities

### New Capabilities

- `order-item-idempotency`: 下單的冪等性——`Idempotency-Key` 的格式與作用範圍(每位使用者)、首次成功結果的保存與重送時的回放、失敗不留記錄、同一 key 併發請求的處理、記錄的保存期限

### Modified Capabilities

(無。`openspec/specs/` 目前沒有下單相關的 capability;`create_user_order` 的既有規則記載於 `docs/SPEC.md`,本變更不改其驗證、凍結與快照規則,僅新增回應欄位 `itemId` 與上述冪等行為。)

## Impact

- **程式碼**:`OrderController.createUserOrder`(讀取 header、回傳 `itemId`)、`OrderService.createUserOrder`(在既有交易內處理 key,改為回傳 `itemId`);新增 key 記錄的 entity 與 mapper;新增清理過期記錄的排程工作
- **DB**:新增 Flyway migration `V10`,建立 key 記錄表,`(user_id, idempotency_key)` 唯一索引、`created_at` 索引(供清理使用)
- **API**:`POST /order/create_user_order` 新增選填 header `Idempotency-Key`、回應新增 `itemId`
- **測試**:`OrderController` 整合測試新增重送回放、失敗後重試、不同使用者同 key、不帶 key 維持原行為;同一 key 併發請求只寫入一筆的併發測試;清理只刪除過期記錄的測試;既有 `OrderServiceTest` 因回傳值改變需調整
- **文件**:`docs/SPEC.md` 的 `create_user_order` 章節(header、回應、業務邏輯)與 DB schema 章節
- **不影響**:凍結金額計算、結算與扣款邏輯、WebSocket 推播、Redis
