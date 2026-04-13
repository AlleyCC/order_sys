## 1. 前置盤點

- [x] 1.1 diff 現有 `README.md` 與 `CLAUDE.md` 的指令段落，列出 README 獨有、`CLAUDE.md` 未涵蓋的內容（結論：CLAUDE.md 已涵蓋所有操作指令，無遺漏）
- [x] 1.2 判定是否需新增 `docs/DEVELOPMENT.md`：不需要
- [x] 1.3 略（1.2 判定不需要）

## 2. README 主體撰寫

- [x] 2.1 撰寫首屏區塊：專案標題「PayPool - 團購訂單管理系統」、一句話定位、CI badge（placeholder 或正式 URL）、技術棧徽章列（Java 25 / Spring Boot 3.5 / MySQL / Redis）
- [x] 2.2 撰寫「架構」章節：ASCII box-and-arrow 圖，涵蓋 Controller / Service / Mapper、MySQL、Redis、WebSocket，標示 JWT Auth flow 與通知 Pub/Sub flow；圖寬限制 ≤ 80 字元
- [x] 2.3 撰寫「技術決策」章節三項敘事：Token 遷 Redis、結算隊列改 Redis、AOP vs @ControllerAdvice；每項用「問題 / 選擇 / 為何」三段式，2-3 句
- [x] 2.4 撰寫「Quick Start」章節：三條指令（`docker compose up -d` → `./mvnw package -DskipTests` → `java -jar target/*.jar`），並附啟動後驗證方式（Swagger UI 連結或健康檢查）
- [x] 2.5 撰寫「API 示例」章節：一個登入 curl（含 access/refresh token 回應）、一個需認證的業務 API curl（含 Authorization header 與回應）
- [x] 2.6 撰寫「API 文件」章節指向 `/swagger-ui.html`，註記需先啟動應用程式
- [x] 2.7 撰寫頁尾：License（若有）、作者、相關 docs 入口（`docs/SPEC.md`、`openspec/`）

## 3. 搬遷與清理

- [x] 3.1 從 README 移除所有已搬遷或已涵蓋於 `CLAUDE.md` 的操作指令段落（保留系統設計資產如 ERD、sequence/state diagrams，刪除 Flyway/build 指令）
- [x] 3.2 確認 README 沒有殘留 Spring Boot 產生器的樣板文字（`HELP.md` reference 等）

## 4. 驗收

- [ ] 4.1 於 GitHub 頁面預覽 README：檢查徽章顯示、ASCII 架構圖對齊、連結可點
- [ ] 4.2 模擬「面試官 60 秒掃 repo」：計時 60 秒讀首屏，能否抓到「是什麼 / 用了什麼 / 跑起來長什麼樣」
- [ ] 4.3 模擬「開發者找指令」：確認 Flyway、DB access、build 指令皆可於 `CLAUDE.md` 或 `docs/DEVELOPMENT.md` 找到
- [x] 4.4 CI badge 已使用正式 URL：`https://github.com/AlleyCC/order_sys/actions/workflows/ci.yml/badge.svg`
- [ ] 4.5 執行 `openspec archive readme-rewrite` 將本 change 歸檔
