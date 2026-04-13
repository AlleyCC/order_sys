## ADDED Requirements

### Requirement: 首屏敘事
README 必須在首屏（無須捲動，約前 30 行）讓讀者掌握：專案是什麼、用什麼技術、當前健康狀態。

#### Scenario: 讀者首次打開 README
- **WHEN** 讀者從 GitHub 進入 repo 首頁
- **THEN** 首屏可見：專案名稱、一句話定位（團購訂單系統）、CI build status badge、技術棧徽章列（至少 Java 25、Spring Boot 3.5、MySQL、Redis）

#### Scenario: CI 徽章行為
- **WHEN** main 分支最近一次 CI workflow 成功
- **THEN** badge 顯示綠色 "passing"，點擊可導向對應 workflow 執行頁面

#### Scenario: CI 徽章異常狀態
- **WHEN** main 分支最近一次 workflow 失敗
- **THEN** badge 顯示紅色 "failing"，讀者可立刻察覺

### Requirement: 架構圖
README 必須提供一張涵蓋所有分層的架構圖，使用 ASCII box-and-arrow 繪製於 fenced code block 內。

#### Scenario: 讀者閱讀架構章節
- **WHEN** 讀者向下捲動到「架構」章節
- **THEN** 看到一張分層圖涵蓋：Controller / Service / Mapper、MySQL、Redis、WebSocket，並標示 JWT Auth 流向與通知（Pub/Sub）流向

#### Scenario: 跨平台顯示
- **WHEN** 讀者以 GitHub、VS Code preview、或 mobile GitHub App 檢視
- **THEN** 架構圖皆可正確顯示、對齊不亂（因使用 ASCII 而非 mermaid）

### Requirement: 技術決策敘事
README 必須為三個核心技術決策提供 2-3 句「問題 → 選擇 → 為何」敘事。

#### Scenario: Token 儲存決策
- **WHEN** 讀者閱讀「技術決策」章節
- **THEN** 看到關於 Token 從 DB 遷移到 Redis 的說明（問題：DB 頻繁讀寫壓力 / 選擇：Redis + TTL / 為何：天生支援過期、延遲低）

#### Scenario: 結算隊列決策
- **WHEN** 讀者閱讀同章節
- **THEN** 看到關於結算隊列從 in-memory 改為 Redis delay queue 的說明（問題：單機記憶體不支援多實例 / 選擇：Redis ZSET / 為何：跨實例一致、支援延遲觸發）

#### Scenario: AOP 日誌決策
- **WHEN** 讀者閱讀同章節
- **THEN** 看到關於用 AOP 做 access log 而非 `@ControllerAdvice` 的說明（問題：要記方法耗時 / 選擇：`@Around` / 為何：`@ControllerAdvice` 無 around 語義，且 AOP 可擴至 Service 層）

### Requirement: Quick Start
README 必須提供可複製貼上即可運作的本機啟動流程，限制為三條核心指令。

#### Scenario: 讀者想本機跑起來
- **WHEN** 讀者照著「Quick Start」章節操作
- **THEN** 依序執行 `docker compose up -d`、`./mvnw package -DskipTests`、`java -jar target/orderSystem-0.0.1-SNAPSHOT.jar` 即可在 `localhost:8591` 啟動服務

#### Scenario: 啟動後驗證
- **WHEN** 讀者於上述步驟後開啟瀏覽器
- **THEN** README 指引其造訪 Swagger UI (`/swagger-ui.html`) 或健康檢查端點以確認啟動成功

### Requirement: API 示例
README 必須提供至少兩個代表性 API 的 curl 範例（登入 + 業務操作），附回應 JSON。

#### Scenario: 認證示例
- **WHEN** 讀者閱讀「API 示例」章節
- **THEN** 看到 `POST /api/auth/login` 的 curl 指令與 access/refresh token 回應範例

#### Scenario: 業務示例
- **WHEN** 讀者閱讀同章節
- **THEN** 看到一個需認證的業務 API（如建立訂單或加入團購）的 curl 指令，含 `Authorization: Bearer <token>` header 與預期回應

### Requirement: Swagger UI 連結
README 必須引導讀者找到互動式 API 文件介面，而非在 README 內窮舉端點。

#### Scenario: 讀者想看完整 API 列表
- **WHEN** 讀者閱讀 API 章節或 Quick Start 完成後
- **THEN** 看到 `/swagger-ui.html` 的連結說明，並提示需先啟動應用程式

### Requirement: 操作指令搬遷
舊 README 中的開發操作指令（build、Flyway、DB access 等）必須搬遷至 `docs/DEVELOPMENT.md` 或確認已涵蓋於 `CLAUDE.md`，不保留於新 README。

#### Scenario: 開發者找建置指令
- **WHEN** 開發者（非面試官）想查 Flyway migration 指令
- **THEN** 能在 `CLAUDE.md` 或 `docs/DEVELOPMENT.md` 其中之一找到，不遺失於重寫過程
