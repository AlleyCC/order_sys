## 1. CI Pipeline

- [x] 1.1 建立 `.github/workflows/ci.yml` 基礎框架：`on: [push, pull_request]`、單一 `test` job、`runs-on: ubuntu-latest`
- [x] 1.2 加入 `actions/checkout@v4` 步驟
- [x] 1.3 加入 `actions/setup-java@v4` 步驟（`distribution: temurin`、`java-version: '25'`、`cache: maven`）
- [x] 1.4 加入「Generate RSA keys」步驟：`openssl genrsa -out key/private_key.pem 2048` + 對應 public key + password key 對（共 4 個檔案）
- [x] 1.5 加入 `./mvnw test` 執行步驟
- [x] 1.6 Commit 並 push 到 feature 分支，確認 workflow 於 GitHub Actions 頁面綠燈
- [x] 1.7 處理首次執行暴露的問題（Java 版本安裝、Testcontainers 啟動、Maven cache key 等）直到穩定

## 2. Swagger / OpenAPI UI

- [x] 2.1 於 `pom.xml` 新增 `springdoc-openapi-starter-webmvc-ui` 依賴（確認相容 Spring Boot 3.5.x 的版本）
- [x] 2.2 本機 `./mvnw spring-boot:run` 啟動後驗證 `http://localhost:8591/swagger-ui.html` 可載入
- [x] 2.3 驗證 `/v3/api-docs` 回傳合法 OpenAPI 3.1 JSON
- [x] 2.4 於 `AuthController` 加 `@Tag(name = "Auth", description = "...")`、每個 endpoint 加 `@Operation(summary = "...")`
- [x] 2.5 於 `OrderController` 加 `@Tag` 與 endpoint 層級 `@Operation`
- [x] 2.6 於 `UserController` 加 `@Tag` 與 endpoint 層級 `@Operation`
- [x] 2.7 於 Swagger UI 驗證：端點依 Controller 分組、每組顯示 summary
- [x] 2.8 新增 `OpenApiConfig` `@Configuration`：宣告 `OpenAPI` bean 與 `SecurityScheme(bearer)`，驗證 Swagger UI「Authorize」按鈕可填入 JWT token
- [x] 2.9 `./mvnw test` 確認新增依賴與註解未影響既有測試（103/103 通過）

## 3. 驗收與收尾

- [x] 3.1 確認 CI workflow 於 main 分支為綠色，記下 badge URL 供其他 change 使用
- [x] 3.2 確認 Swagger UI 於本機啟動可正常互動（含需 JWT 的端點）
- [x] 3.3 執行 `openspec archive portfolio-polish-phase1` 將本 change 歸檔
