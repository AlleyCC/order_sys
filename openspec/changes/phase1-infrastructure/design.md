## Context

PayPool 是一個團購訂單管理系統，目前只有 Spring Boot 主類 (`OrderSystemApplication.java`) 和 Flyway migration SQL (`V1__init_schema.sql`)。需要建立完整的開發環境基礎設施，讓後續 Phase 2 的 TDD API 開發可以順利進行。

技術棧已確定：Java 25、Spring Boot 3.5.13、MySQL 8.0、MyBatis-Plus、JJWT (RS256)、Bouncy Castle、Testcontainers。

## Goals / Non-Goals

**Goals:**
- 一個 `docker compose up -d` 即可啟動 MySQL 開發環境
- `mvn spring-boot:run` 可成功連線 MySQL 並執行 Flyway migration
- pom.xml 包含所有後續開發所需的 dependencies
- 清晰的 package 骨架供後續 API 開發使用
- Key 檔案不進版控，路徑可透過 Spring Profile 切換

**Non-Goals:**
- 不實作任何業務代碼、API、Security filter
- 不寫測試（Phase 2 開始）
- 不容器化 Spring Boot app
- 不設定 CI/CD

## Decisions

### 1. MySQL container only, app runs locally

**選擇**: Docker Compose 只起 MySQL，Spring Boot app 在本機跑。

**理由**: 開發階段需要頻繁修改代碼並即時測試，本機跑 app 搭配 IDE hot-reload 更高效。若 app 也容器化，每次改 code 都要 rebuild image。

**替代方案**: app + MySQL 都容器化 → 開發體驗差，Phase 1 不需要。

### 2. Named volume for DB data, bind mount for logs

**選擇**:
- `mysql-data` (named volume) → `/var/lib/mysql`
- `./logs/mysql` (bind mount) → `/var/log/mysql`

**理由**: Named volume 由 Docker 管理，效能好且不會因路徑問題出錯，適合存 DB data。Bind mount 讓開發者可以直接在本機檔案系統瀏覽 log，不需要 `docker exec`。

### 3. Jackson 取代 FastJSON

**選擇**: 使用 Spring Boot 內建的 Jackson。

**理由**: FastJSON 1.2.79 有多個已知 CVE（反序列化漏洞），且 Spring Boot 原生整合 Jackson，零配置即可使用。所有 JSON 序列化需求 Jackson 都能滿足。

**替代方案**: FastJSON 2.x → 安全性改善但仍非 Spring Boot 原生，額外依賴無必要。

### 4. Testcontainers 取代 H2

**選擇**: 測試使用 Testcontainers 起真正的 MySQL container。

**理由**: Schema 中使用了 MySQL 特有語法（`ENGINE=InnoDB`、`utf8mb4_0900_ai_ci`、`GENERATED ALWAYS AS ... STORED`），H2 即使開 MySQL 相容模式也無法完全支援，需要維護兩份 schema。Testcontainers 直接用 Flyway migration，確保測試環境與生產一致。

**替代方案**: H2 + MySQL 相容模式 + 測試用 schema → 維護成本高，容易漏洞。

### 5. Spring Profiles 管理 key paths

**選擇**:
- `application.properties`: 預設路徑 `../key/*`（spec 定義的標準路徑）
- `application-dev.properties`: override 為 `./key/*`（本機開發便利）

**理由**: Key 檔案不進版控，放在專案外 (`../key/`) 是安全做法。但本機開發時 key 可能在專案內，用 dev profile 靈活切換。

### 6. Package 結構：按技術層分包

**選擇**:
```
com.example.orderSystem
├── config/          # Spring 配置
├── security/        # JWT filter
├── controller/      # REST controllers
├── service/         # 業務邏輯
├── mapper/          # MyBatis-Plus mappers
├── entity/          # 資料庫實體
├── dto/             # request/response DTOs
├── enums/           # 列舉
├── exception/       # 例外處理
├── scheduler/       # DelayQueue
├── websocket/       # WebSocket handlers
└── util/            # 工具類
```

**理由**: 專案規模不大（約 20 幾個檔案），按技術層分包結構簡單直覺，找檔案不用多點好幾層。按 domain 分包對此規模過度設計。

### 7. JJWT 版本選擇

**選擇**: jjwt 0.12.x（最新穩定版，支援 Java 17+ 和 Spring Boot 3.x）。

**理由**: Spec 中寫的 0.11.2 是舊版，0.12.x 對 Java 17+ 有更好的支援，API 也更簡潔。既然用 Java 25，應搭配最新版。

### 8. MySQL slow query log threshold

**選擇**: 1 秒。

**理由**: 開發環境用於發現明顯的慢查詢，1 秒是合理的預設值。不開 general log（記錄所有 query，太吵）。

## Risks / Trade-offs

- **Java 25 non-LTS** → 未來可能需要遷移到下一個 LTS (Java 29?)。Mitigation: 目前無 Java 25 特有語法依賴，遷移成本低。
- **Key 檔案管理** → 新開發者需手動準備 `../key/` 或 `./key/` 目錄及金鑰。Mitigation: 在 README 或 CLAUDE.md 說明，或提供 key generation script（不在此 Phase scope）。
- **Testcontainers 需要 Docker** → 執行測試時必須有 Docker daemon running。Mitigation: 開發環境已經需要 Docker（MySQL container），所以這不是額外要求。
- **pom.xml 加入尚未使用的依賴**（如 websocket、security）→ 稍微增加 build 時間。Mitigation: 影響微小，且避免後續 Phase 反覆修改 pom.xml。
