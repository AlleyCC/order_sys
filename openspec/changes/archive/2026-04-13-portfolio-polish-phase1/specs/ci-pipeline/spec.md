## ADDED Requirements

### Requirement: CI 觸發條件
系統必須在每次 push 到任一分支、以及每個針對 main 的 pull request 時，自動執行 CI workflow。

#### Scenario: 開發者 push feature 分支
- **WHEN** 開發者執行 `git push origin feature/xxx`
- **THEN** GitHub Actions 偵測並啟動 `ci.yml` workflow 對該 commit 跑測試

#### Scenario: 開發者開啟 PR
- **WHEN** 開發者對 main 開啟 pull request
- **THEN** workflow 自動對該 PR 的 head commit 執行測試，結果顯示於 PR 頁面的 checks 區塊

### Requirement: Java 25 執行環境
CI 必須使用 Java 25（Temurin 發行版），與專案生產環境版本一致，不為 CI 降版。

#### Scenario: Workflow 設定 JDK
- **WHEN** workflow 執行 `setup-java` 步驟
- **THEN** 使用 `distribution: temurin`、`java-version: '25'`，並啟用 Maven 快取

### Requirement: 測試用 RSA key 臨時生成
CI 執行測試前，必須於 runner 上臨時生成測試用的 RSA key 對（JWT + 密碼加密），置於 `key/` 目錄，不得將任何 key 檔案 commit 進 repo。

#### Scenario: 測試啟動前
- **WHEN** workflow 進入「Generate RSA keys」步驟
- **THEN** 於 runner 上以 `openssl` 產生 `private_key.pem`、`public_key.pem`、`password_private.key`、`password_public.key` 至 `key/` 目錄

#### Scenario: Key 不外流
- **WHEN** 檢視 repo 檔案或 artifact
- **THEN** 找不到任何 `.pem` 或 `.key` 檔案被 commit，且 workflow artifact 不包含 key 檔

### Requirement: 測試套件執行
CI 必須執行完整 `./mvnw test`，涵蓋所有 unit / integration / Testcontainers 測試，任一測試失敗即整個 workflow 失敗。

#### Scenario: 全部測試通過
- **WHEN** `./mvnw test` 全部通過
- **THEN** workflow 以綠燈結束，對應 commit 顯示成功標記

#### Scenario: 任一測試失敗
- **WHEN** 任何 test class 有失敗 case
- **THEN** workflow 以紅燈結束，PR checks 區塊阻擋合併（若 branch protection 啟用）

### Requirement: Maven 依賴快取
CI 必須快取 Maven local repository（`~/.m2/repository`），以縮短後續執行時間。

#### Scenario: 首次執行無快取
- **WHEN** 首次執行 workflow 或 `pom.xml` 變更
- **THEN** Maven 從遠端下載依賴，並將 `~/.m2/repository` 存入 GitHub Actions cache

#### Scenario: 後續執行命中快取
- **WHEN** 後續執行且 `pom.xml` 未變更
- **THEN** Maven 從快取還原，依賴下載時間接近零
