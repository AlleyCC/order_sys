## Context

目標讀者是**技術面試官**，典型行為：60 秒掃 GitHub repo 首頁 → 有感就讀 README 首屏 1-2 分鐘 → 最多點一個檔案。**不會**做的事：clone 下來跑、讀測試、研究 commit history。所以 README 的設計必須為「60 秒建立印象 + 2 分鐘建立信任」服務。

現有 `README.md` 是 Spring Boot 產生器的殘留 + 一些操作指令。沒有敘事、沒有架構圖、沒有技術決策說明。本 change 完全重寫，但要謹慎處理既有操作指令的搬遷以免破壞開發者工作流。

## Goals / Non-Goals

**Goals:**
- 首屏（無須捲動）即顯示：專案定位 + 技術棧徽章 + 架構圖入口
- 三分鐘內能讀完全文並產生「這個人懂系統設計」的判斷
- 所有指令皆可複製貼上直接執行，不須修改
- 保留既有操作指令不致遺失（搬遷 or 確認 CLAUDE.md 已涵蓋）

**Non-Goals:**
- 不做英文版
- 不重寫 `docs/SPEC.md`、不修改 `CLAUDE.md` 的現有規則
- 不在 README 塞完整 API 規格（那是 Swagger UI 的工作）
- 不做多版本 / 多語系切換

## Decisions

### 1. 架構圖採 ASCII box-and-arrow，不用 mermaid

- **選擇**：頂層架構圖以 ASCII 繪製於 fenced code block
- **替代方案**：mermaid `flowchart TD`（GitHub 原生渲染為圖片）
- **理由**：
  - ASCII 在任何平台、任何 fork、任何離線閱讀器都能顯示
  - mermaid 在 GitHub 渲染但在 VS Code preview、部分 RSS 閱讀器、mobile 瀏覽器體驗不一
  - 面試官可能於非 GitHub 介面（如 Notion 複製連結預覽）瀏覽
  - 需要複雜流程時才考慮 mermaid（例如單一章節的 sequence diagram）

### 2. README 首屏徽章只放 build status，不加覆蓋率/版本

- **選擇**：僅一個 GitHub Actions build status badge
- **替代方案**：加上 coverage、license、Java 版本、Spring Boot 版本等多個 badge
- **理由**：
  - 多 badge 擠壓首屏架構圖可視面積
  - coverage badge 需要 JaCoCo + Codecov，超出本 change 範圍
  - 單一綠燈 badge 已足以建立「此 repo 有 CI」的信號

### 3. 技術決策敘事限縮為三項，不做大而全

- **選擇**：只寫三個最有故事性的決策 —— (1) Token 從 DB 遷到 Redis、(2) 結算隊列 in-memory → Redis、(3) AOP 做 access log 而非 `@ControllerAdvice`
- **替代方案**：為每個 phase（phase1-10）都寫決策記錄
- **理由**：
  - 三個就夠用 —— 每個都能講 2-3 分鐘，合起來就是面試故事
  - 大而全的決策表讀者不會看完，且跟 openspec/ 重疊
  - 每項敘事格式統一：「問題 → 選擇 → 為何」

### 4. 既有操作指令以「搬遷優先、新增其次」處理

- **選擇**：先 diff 既有 README 的指令段落 vs `CLAUDE.md`，若 `CLAUDE.md` 已涵蓋則刪除；未涵蓋則新增 `docs/DEVELOPMENT.md`
- **替代方案**：保留於 README 作為「Development」章節
- **理由**：
  - 目標讀者（面試官）不需要 Flyway 指令
  - 保留會稀釋首屏的敘事密度
  - 開發者工作流透過 `CLAUDE.md` 或新文件維持

### 5. Quick Start 限制為三行指令，不做進階設定

- **選擇**：`docker compose up -d` → `./mvnw package -DskipTests` → `java -jar target/*.jar`
- **替代方案**：包含環境變數、自訂 port、Profile 切換等選項
- **理由**：
  - 面試官不會真的跑，但會掃這三行判斷「複雜度」
  - 三行代表「依賴啟動 → 建置 → 執行」，故事性完整
  - 進階設定留到 `docs/DEVELOPMENT.md`

### 6. API 示例採 curl，不用 HTTP 純文字或螢幕截圖

- **選擇**：一個登入 + 一個建立訂單的 curl 範例，含回應 JSON
- **替代方案 A**：只放 Swagger UI 截圖
- **替代方案 B**：HTTP 文字格式（`POST /api/auth/login ...`）
- **理由**：
  - curl 可直接複製到 terminal 跑，體感最直觀
  - 截圖會隨 UI 版本過時
  - 兩個範例足以展示「認證 + 業務」的互動脈絡

### 7. CI badge 依賴處理：暫以 placeholder，工作流完成後回填

- **選擇**：初版先放待填的 badge markdown，等 `portfolio-polish-phase1` 的 CI 完成後，取得正式 URL 再回填
- **替代方案**：等 CI 完全就緒才開始本 change
- **理由**：
  - 兩個 change 可並行進行；本 change 其他內容不依賴 CI
  - 避免 change 之間強耦合
  - 回填是一行 markdown 修改，成本極低

## Risks / Trade-offs

- **[重寫過程誤刪 `CLAUDE.md` 未涵蓋的操作指令]** → Mitigation：搬遷前逐段 diff，未涵蓋者明確移入 `docs/DEVELOPMENT.md` 再刪除原文
- **[ASCII 圖在不同字體下對齊走版]** → Mitigation：使用等寬 ASCII 並限制寬度 ≤ 80 字元；僅用 `┌─┐│└─┘│` 等標準 box-drawing 字元
- **[技術決策敘事偏離事實]** → Mitigation：三項決策都有對應的 phase change（phase9/10、add-aop-logging），撰寫時對照 design.md 確認
- **[CI badge 尚未就緒導致 README 首屏顯示破圖]** → Mitigation：placeholder 階段不放圖片 tag，改用文字 `![CI](TBD)`；回填時改為正式 URL
