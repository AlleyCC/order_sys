## 1. Setup

- [x] 1.1 在 pom.xml 加入 `spring-boot-starter-aop` 依賴
- [x] 1.2 新增 `com.example.orderSystem.aspect` package

## 2. Core Implementation

- [x] 2.1 建立 `ApiAccessLogAspect` 類別，設定 `@Aspect` + `@Component`
- [x] 2.2 實作 `@Around("within(com.example.orderSystem.controller..*)")` pointcut
- [x] 2.3 記錄 HTTP method、URI（從 RequestContextHolder 取得 HttpServletRequest）
- [x] 2.4 從 SecurityContextHolder 取得 userId，未認證顯示 `anonymous`
- [x] 2.5 實作敏感欄位脫敏（password / token / secret 參數顯示 `[MASKED]`）
- [x] 2.6 參數 toString 超過 500 字元時截斷並附加 `...`
- [x] 2.7 正常回傳用 INFO log，例外用 WARN log 並 rethrow

## 3. Testing

- [x] 3.1 寫整合測試驗證正常請求產生 INFO log（含 method、URI、userId、耗時）
- [x] 3.2 寫整合測試驗證異常請求產生 WARN log（含 exception 資訊）
- [x] 3.3 寫測試驗證 password 欄位被脫敏為 `[MASKED]`
- [x] 3.4 執行 `./mvnw test` 確認所有既有測試不受影響
