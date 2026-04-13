## Context

本專案已完成 phase1-10 共 10 個 OpenSpec change，涵蓋訂單、Auth、Redis 遷移、WebSocket、AOP 日誌等完整功能。當前缺少兩項工程門面：沒有互動式 API 文件介面、沒有 CI。本 change 聚焦於這兩項基礎設施補齊，工時預估 2-3 小時。

設計原則：

- 最小侵入 —— 純加法，不改動既有應用程式邏輯
- 最小工時 —— 超過 3 小時即視為超出本 change 成本假設，應另開 change
- 門面定位 —— 這兩項是「credibility 門檻」，不是技術賣點

## Goals / Non-Goals

**Goals:**
- Swagger UI 在本機啟動後一鍵可互動，不需 Postman，且可測試需 JWT 的端點
- CI workflow 產出綠燈狀態，對應 GitHub Actions build badge URL 可供其他文件引用
- 兩項變更彼此獨立，可各自回退

**Non-Goals:**
- 不做 Dockerfile / app 容器化 / CD / 雲端部署
- 不做 JaCoCo 覆蓋率、matrix JDK、Dependabot、PR template、branch protection
- 不做效能優化、新功能、現有邏輯重構
- 不做 Redisson / Prometheus / k6（Phase 2 候選）
- 不碰 `README.md`（由獨立 change 處理）

## Decisions

### 1. Java 版本維持 25，不降版

- **選擇**：CI 與本地皆使用 Java 25（Temurin）
- **替代方案**：CI 降版到 Java 21 LTS 以換取穩定性
- **理由**：
  - Java 25 是專案的技術亮點，降版等同抹掉賣點
  - Temurin 25 自 2025-10 起於 GitHub Actions 穩定可用，`setup-java@v4` 原生支援
  - 風險實測約 5%，不足以交換亮點損失

### 2. Swagger 選 springdoc-openapi 而非 springfox

- **選擇**：`springdoc-openapi-starter-webmvc-ui`（最新版）
- **替代方案**：`springfox-boot-starter`
- **理由**：
  - springfox 已長期未維護、不相容 Spring Boot 3.x
  - springdoc 為當前 Spring Boot 3 生態官方推薦
  - 原生支援 Spring Security、Jakarta EE 命名空間

### 3. Controller 註解採「簡標註」策略，不做全量註解

- **選擇**：每個 Controller 加 `@Tag`、每個 public endpoint 加 `@Operation(summary)`，不寫 `@Parameter`、`@ApiResponse` 等細節註解
- **替代方案**：完整標註（含請求/回應 schema、範例、錯誤碼）
- **理由**：
  - Swagger UI 能從 DTO 型別自動推導 schema，不需手寫
  - 完整標註工時 >> 簡標註 5-10 倍，邊際效益低
  - 保留 70% 的視覺效益，換取 20% 的工時

### 4. JWT Bearer 認證以單一 OpenAPI config 支援

- **選擇**：新增一個 `@Configuration` class，宣告 `OpenAPI` bean 帶 `SecurityScheme(bearer)`，Controller 上用 `@SecurityRequirement` 標記需認證的端點（或於 bean 中 addSecurityItem 全域套用）
- **替代方案**：每個受保護端點逐一標註 `@SecurityRequirement`
- **理由**：
  - 專案多數端點需 JWT，全域套用 + 公開端點用 `@SecurityRequirement` 清空，邏輯反轉但減少標註量
  - 可在 Swagger UI 右上 Authorize 按鈕輸入一次 token，所有端點共用

### 5. RSA key 在 CI 內臨時生成，不改動 app code

- **選擇**：workflow 中用 `openssl genrsa` / `rsa -pubout` 產生 4 個 dummy key 至 `key/`
- **替代方案 A**：改 test profile，測試啟動時自動生成到 temp dir
- **替代方案 B**：把測試用 key commit 進 `src/test/resources/keys/`
- **理由**：
  - 方案 A 要改應用碼，影響本次 change 範圍（本 change 應為純加法）
  - 方案 B 雖最簡單，但 GitHub secret scanner 會對 `.pem` / `.key` 檔發警告（即使是 dummy key），公開 repo 形象扣分
  - 當前方案 workflow 增加 ~6 行，零 app code 改動

### 6. CI workflow 刻意極簡（單 job、單 step 集合）

- **選擇**：一個 `test` job，按順序：checkout → setup-java → cache → gen-key → test
- **替代方案**：拆分 `lint` / `unit` / `integration` / `build` 多 job 平行
- **理由**：
  - 本 change 定位為「credibility 門檻」而非「技術賣點」
  - 超過 30 分鐘投資違反成本假設
  - 未來若要擴展（Phase 2），可另開 change 演進此 workflow

## Risks / Trade-offs

- **[Java 25 on CI 意外不穩]** → Mitigation：若 Temurin 25 拉不到映像，臨時 fallback 到 `oracle` distribution；真的都不行則開 follow-up change 評估降版
- **[Swagger 註解汙染 Controller 可讀性]** → Mitigation：註解限制在 class/method 層級的簡短 `summary`，避免欄位級大量註解
- **[CI workflow 執行時間過長（>10 分鐘）影響體驗]** → Mitigation：Maven cache + 單 job 設計，目標 5 分鐘內完成；首次無快取約 8 分鐘可接受
- **[RSA key 生成指令在不同 openssl 版本行為差異]** → Mitigation：Ubuntu runner 預裝 openssl 3.x 穩定；若遇問題改用 `ssh-keygen` 或 Java `KeyPairGenerator` 腳本
- **[SecurityRequirement 全域套用導致公開端點誤鎖]** → Mitigation：Swagger UI 不影響實際 Security Filter 設定，最壞狀況是 UI 顯示需 token 但實際 API 可匿名，仍可運作

## Migration Plan

本 change 為純加法，無遷移步驟。部署即生效：

1. 合併 PR → main 分支自動觸發新的 CI workflow
2. Swagger UI 於下次應用程式啟動後可用（`/swagger-ui.html`）

**回退策略**：兩項變更彼此獨立，可分別回退而不影響另一項。

## Open Questions

- Swagger UI 在生產環境是否該關閉?（預設關閉 `springdoc.swagger-ui.enabled=false` 於 `application-prod.properties`，但本專案目前無 prod profile，暫不處理）
- CI workflow status badge 的外部引用由其他 change 負責；本 change 只需確保 workflow 產生穩定可取用的 badge URL
