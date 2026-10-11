## Purpose

限制登入嘗試的次數,讓暴力破解、密碼噴灑與分散式猜測都無法無限次嘗試密碼,同時不讓攻擊者藉由限流把帳號本人鎖在門外。

## ADDED Requirements

### Requirement: 依來源 IP 限制登入嘗試

系統 SHALL 依來源 IP 計算登入嘗試次數(不論成功或失敗、不論帳號為何),在滑動時間窗內超過上限時拒絕該 IP 的登入請求。預設上限為每 1 分鐘 5 次。來源 IP MUST 取自直接連線的對端位址,MUST NOT 採信 `X-Forwarded-For` 等由客戶端提供的 header。

#### Scenario: 同一 IP 嘗試過多

- **WHEN** 同一 IP 在 1 分鐘內對任意帳號送出第 6 次登入請求
- **THEN** 系統回覆 HTTP 401「帳密錯誤」,不驗證密碼

#### Scenario: 不同 IP 互不影響

- **WHEN** IP A 已達上限,IP B 以正確帳密登入
- **THEN** IP B 登入成功

#### Scenario: 偽造 X-Forwarded-For 無法繞過

- **WHEN** 同一連線來源每次請求帶不同的 `X-Forwarded-For` 值,累計超過上限
- **THEN** 系統仍以連線來源計數並拒絕

### Requirement: 依帳號與來源 IP 限制登入嘗試

系統 SHALL 依「帳號 + 來源 IP」計算登入嘗試次數,在滑動時間窗內超過上限時拒絕該組合的登入請求。預設上限為每 15 分鐘 5 次。該組合登入成功時 MUST 將其計數歸零。此限制 MUST 只影響該來源 IP 對該帳號的登入,不影響同一帳號從其他 IP 登入。

#### Scenario: 同一 IP 對同一帳號猜錯過多

- **WHEN** 同一 IP 對帳號 alice 已嘗試 5 次皆失敗,再送出第 6 次(即使密碼正確)
- **THEN** 系統回覆 HTTP 401「帳密錯誤」

#### Scenario: 帳號本人從其他 IP 不受影響

- **WHEN** 攻擊者 IP 已使 alice + 攻擊者 IP 達上限,alice 從另一個 IP 以正確密碼登入
- **THEN** 登入成功

#### Scenario: 登入成功後歸零

- **WHEN** 同一 IP 對 alice 失敗 4 次後以正確密碼登入成功,接著再失敗 4 次
- **THEN** 第 5 次登入請求仍會進行密碼驗證(不被限流拒絕)

### Requirement: 依帳號全域限制登入嘗試,受信任 IP 豁免

系統 SHALL 依帳號計算來自「不受信任 IP」的登入嘗試次數,在滑動時間窗內超過上限時拒絕不受信任 IP 對該帳號的登入請求。預設上限為每 1 小時 20 次。曾經成功登入該帳號的 IP MUST 在 30 天內視為該帳號的受信任 IP;受信任 IP 的嘗試 MUST NOT 計入此限制,也 MUST NOT 被此限制拒絕(仍受其他兩層限制)。此限制達上限後 MUST NOT 以登入成功歸零,只隨時間窗滑動恢復。

#### Scenario: 分散式 IP 猜同一帳號

- **WHEN** 20 個從未成功登入 alice 的不同 IP,在 1 小時內各對 alice 嘗試 1 次,接著第 21 個不受信任 IP 嘗試登入 alice
- **THEN** 系統回覆 HTTP 401「帳密錯誤」,不驗證密碼

#### Scenario: 帳號本人從受信任 IP 仍可登入

- **WHEN** alice 的全域限制已達上限,alice 從過去 30 天內曾成功登入的 IP 以正確密碼登入
- **THEN** 登入成功

#### Scenario: 帳號本人從新 IP 會被擋

- **WHEN** alice 的全域限制已達上限,alice 從從未成功登入過的 IP 以正確密碼登入
- **THEN** 系統回覆 HTTP 401「帳密錯誤」

### Requirement: 被限流的回應與帳密錯誤無法區分

被任何一層限流拒絕的登入請求,系統 MUST 回覆與帳密錯誤完全相同的 HTTP 狀態碼與內容(HTTP 401,`detail` 為「帳密錯誤」),MUST NOT 帶有可區分的 header(例如 `Retry-After`)。被拒絕的請求 MUST NOT 進行密碼驗證。

#### Scenario: 回應內容一致

- **WHEN** 分別取得一次帳密錯誤的回應與一次被限流拒絕的回應
- **THEN** 兩者狀態碼、body、header(除日期等通用 header 外)相同

### Requirement: 帳號計數不因帳號存在與否或大小寫而不同

帳號 MUST 只包含英數字與 `.` `_` `-`,否則回覆 HTTP 400(資料庫比對帳號時不分大小寫也不分重音,若允許重音字元,`alicé` 與 `alice` 會是同一個帳號卻各自計數)。帳號相關的計數 SHALL 以請求所帶的帳號字串為準,去除頭尾空白並轉為小寫後計數。帳號不存在時 MUST 同樣計數與拒絕,使攻擊者無法從限流行為判斷帳號是否存在。

#### Scenario: 不存在的帳號同樣被限流

- **WHEN** 同一 IP 對不存在的帳號 nobody99 嘗試 6 次
- **THEN** 第 6 次被拒絕,行為與存在的帳號相同

#### Scenario: 改變大小寫無法繞過

- **WHEN** 同一 IP 輪流以 `alice`、`Alice`、`ALICE` 累計嘗試 6 次
- **THEN** 第 6 次被拒絕

#### Scenario: 帳號長度超過上限

- **WHEN** 請求帶的帳號超過 20 個字元
- **THEN** 系統回覆 HTTP 400,不計入任何限流計數

#### Scenario: 帳號含英數字以外的字元

- **WHEN** 請求帶的帳號含重音等字元,例如 `alicé`、`ålice`
- **THEN** 系統回覆 HTTP 400,不計入任何限流計數

### Requirement: 限流元件故障時不中斷登入

當限流元件存取 Redis 發生錯誤(例如逾時、連線重建中),而登入流程的其他部分仍可運作時,系統 SHALL 放行登入請求(照常驗證密碼),並記錄錯誤,MUST NOT 因限流元件故障而拒絕所有登入。Redis 整體無法使用時,登入本身需要寫入 Refresh Token 而無法完成,不在此需求範圍。

#### Scenario: 限流元件存取 Redis 失敗

- **WHEN** 限流元件存取 Redis 時發生連線錯誤,使用者以正確帳密登入
- **THEN** 登入成功
