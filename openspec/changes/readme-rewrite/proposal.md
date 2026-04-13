## Why

目前 `README.md` 僅保留初期樣板內容（Dev Commands、Flyway 指令清單），缺少專案敘事、架構圖、技術決策說明、一鍵啟動流程。實測面試官平均 60 秒掃 repo，90% 停留在 README —— 若首屏無法讓讀者在 60 秒內掌握「這是什麼、用了什麼、怎麼跑」，專案再好也會被跳過。本 change 以讀者為中心重寫 README，把已完成的 phase1-10 功能包裝成可快速理解的故事主線。

## What Changes

- **重寫** `README.md`：首屏定位句、技術棧徽章、ASCII 架構圖、技術決策敘事（三個核心：Token 遷 Redis、結算隊列改 Redis、AOP 做 access log）、Quick Start、API 示例、Swagger UI 連結、CI build status badge
- **搬遷** 既有 `README.md` 中的開發操作指令（build/migration/DB access）到 `docs/DEVELOPMENT.md` 或確認已涵蓋於 `CLAUDE.md`
- **明確不做（out of scope）**：
  - 英文版 README（初版僅中文，後續視需求另開 change）
  - 新增其他文件（ARCHITECTURE.md、CONTRIBUTING.md 等）
  - 專案以外的 docs/SPEC.md、openspec/ 內容修訂

## Capabilities

### New Capabilities
- `project-docs`: 專案對外的 README 文件門面 —— 敘事結構、架構圖呈現、技術決策說明、啟動指引、徽章顯示規範

### Modified Capabilities
<!-- 無 -->

## Impact

- **修改檔案**：
  - `README.md`（整份重寫）
- **可能新增檔案**：
  - `docs/DEVELOPMENT.md`（若 `CLAUDE.md` 未涵蓋所有操作指令）
- **不動到**：應用程式碼、pom.xml、CI workflow、Controller 註解、DB schema
- **依賴新增**：無
- **依賴其他 change**：
  - 本 change 的「Swagger UI 連結」章節預期指向 `/swagger-ui.html`；若 `portfolio-polish-phase1` 尚未完成，該連結可先寫但實際未運作
  - 本 change 的 CI badge 預期引用 `portfolio-polish-phase1` 產生的 workflow URL；若尚未建立，可暫以 placeholder 處理並於該 change 完成後回填
- **回退風險**：極低 —— 純文件變更
