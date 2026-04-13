## Why

專案已完成 phase1-10 全部核心功能（訂單、Auth、Redis 遷移、WebSocket、AOP 日誌），但對外可展示的工程設施尚缺兩項：(1) 互動式 API 文件 — 目前只能靠閱讀 Controller 或手動組 Postman 請求；(2) 自動化測試流程 — 沒有 CI，無法證明「main 分支當下是綠的」。這兩項是現代 Spring Boot 專案的基本配備，缺失會拉低專業感。本 change 以最小工時補齊這兩塊門面基礎設施。

## What Changes

- **新增** Swagger / OpenAPI UI 支援：引入 `springdoc-openapi-starter-webmvc-ui`，`/swagger-ui.html` 可直接操作 API，Controller 加上基本 `@Operation` / `@Tag` 註解，並支援 JWT Bearer 認證測試
- **新增** GitHub Actions CI workflow (`.github/workflows/ci.yml`)：checkout → setup-java 25 (temurin) → cache Maven → 臨時生成 RSA key → `./mvnw test`，~40 行，產出 workflow status badge 供後續外部引用
- **明確不做（out of scope）**：JaCoCo 覆蓋率、Docker build/push、matrix JDK、Dependabot、PR template、Dockerfile（app 容器化）、CD / 雲端部署、Redisson 分散式鎖、Prometheus/Grafana、k6 壓測
- **不屬於本 change**：README 文件重寫由獨立 change 處理

## Capabilities

### New Capabilities
- `api-docs`: API 的自助式文件與互動介面 —— OpenAPI 規格來源、Swagger UI 存取路徑、Controller 註解標準、JWT 認證支援
- `ci-pipeline`: 自動化測試流程 —— 觸發條件、Java 版本策略、RSA key 生成方式、測試範圍、Maven 快取

### Modified Capabilities
<!-- 無：本 change 純加法，不改動既有功能的 spec-level 行為 -->

## Impact

- **新增檔案**：
  - `.github/workflows/ci.yml`
- **修改檔案**：
  - `pom.xml`（新增 springdoc 依賴）
  - Controller 類別（加上 `@Operation` / `@Tag` 註解，不改行為）
  - 可能新增 OpenAPI config class（配置 JWT SecurityScheme）
- **不動到**：應用程式邏輯、DB schema、Flyway migration、既有測試、Redis/MySQL 配置、README.md
- **依賴新增**：`springdoc-openapi-starter-webmvc-ui`（執行期依賴）
- **外部系統**：GitHub Actions（公開 repo 免費額度內）
- **回退風險**：低 —— 兩項變更皆可獨立回退；CI 失敗不影響本地開發
