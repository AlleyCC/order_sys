## ADDED Requirements

### Requirement: 統一記錄 API 存取日誌
系統 SHALL 使用 Spring AOP `@Around` advice 自動攔截所有 Controller 方法，記錄 request 和 response 資訊。

#### Scenario: 正常請求記錄
- **WHEN** 任何 Controller 方法被呼叫並正常回傳
- **THEN** 系統以 INFO level 記錄：HTTP method、URI、userId、方法參數、HTTP status、執行時間(ms)

#### Scenario: 異常請求記錄
- **WHEN** Controller 方法拋出例外
- **THEN** 系統以 WARN level 記錄：HTTP method、URI、userId、方法參數、exception class、exception message、執行時間(ms)
- **THEN** 例外 SHALL 繼續向上拋出，不影響原有的 GlobalExceptionHandler 處理

### Requirement: 敏感欄位脫敏
系統 SHALL 對日誌中的敏感資訊進行脫敏處理，避免密碼或 token 洩漏。

#### Scenario: 含有密碼的請求
- **WHEN** 呼叫 login 等包含 password 欄位的 API
- **THEN** 日誌中該參數值 SHALL 顯示為 `[MASKED]`，不記錄原始值

#### Scenario: 含有 token 的請求
- **WHEN** 請求參數包含 token 或 secret 相關欄位
- **THEN** 日誌中該參數值 SHALL 顯示為 `[MASKED]`

### Requirement: 取得請求者身份
系統 SHALL 從 Spring Security Context 取得當前使用者身份，納入日誌記錄。

#### Scenario: 已認證使用者
- **WHEN** 請求通過 JWT 認證
- **THEN** 日誌中 userId 欄位 SHALL 顯示該使用者的 userId

#### Scenario: 未認證請求
- **WHEN** 請求存取公開 endpoint（如 login、get_all_shops）
- **THEN** 日誌中 userId 欄位 SHALL 顯示 `anonymous`

### Requirement: 參數長度控制
系統 SHALL 對過長的方法參數進行截斷，避免日誌過大。

#### Scenario: 參數超過長度限制
- **WHEN** 方法參數的 toString 結果超過 500 字元
- **THEN** 日誌中 SHALL 截斷至 500 字元並附加 `...`
