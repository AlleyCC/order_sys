## ADDED Requirements

### Requirement: Swagger UI 可用
系統必須提供 `/swagger-ui.html` 路徑，於本機啟動後可直接瀏覽所有 REST API，無需匯入外部工具（Postman、Insomnia）。

#### Scenario: 開啟 Swagger UI
- **WHEN** 應用程式啟動且使用者瀏覽 `http://localhost:8591/swagger-ui.html`
- **THEN** 看到 Swagger UI 介面，列出所有公開的 Controller 端點並可分組展開

#### Scenario: OpenAPI JSON 規格
- **WHEN** 使用者存取 `/v3/api-docs`
- **THEN** 取得符合 OpenAPI 3.1 規範的 JSON 文件，涵蓋所有端點的 path、method、參數、回應

### Requirement: 端點基本標註
每個 Controller 類別必須有 `@Tag` 分組，每個對外 public endpoint 必須有 `@Operation(summary = ...)` 說明其用途。

#### Scenario: 端點分組
- **WHEN** 讀者在 Swagger UI 瀏覽
- **THEN** 看到端點依 Controller 分組（如「Auth」、「Order」、「User」），每組可獨立摺疊展開

#### Scenario: 端點摘要
- **WHEN** 讀者展開任一 endpoint
- **THEN** 看到 summary 簡短描述此 API 功能（例如「登入並取得 access token」、「建立團購訂單」）

### Requirement: 認證端點可直接測試
受 JWT 保護的端點必須在 Swagger UI 中可透過「Authorize」按鈕填入 Bearer token 後直接測試，不需另外工具組裝請求。

#### Scenario: 使用者欲測試需登入的 API
- **WHEN** 使用者點擊 Swagger UI 右上角「Authorize」並貼上 access token
- **THEN** 後續對受保護端點的 Try it out 請求會自動帶上 `Authorization: Bearer <token>` header
