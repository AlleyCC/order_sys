## Why

專案目前只有空的 Spring Boot 主類和 Flyway migration SQL，缺少所有運行所需的基礎設施。沒有資料庫容器、沒有完整的 Maven dependencies、沒有 application 配置。需要先建立可運行的開發環境，才能進入 API 開發階段（TDD 流程）。

## What Changes

- **新增 Docker Compose**：MySQL 8.0 容器，掛載 data volume 和 log bind mount
- **補齊 pom.xml dependencies**：Spring Security、MyBatis-Plus、JJWT、Bouncy Castle、Testcontainers、Validation 等
- **新增 application.properties 配置**：MySQL 連線、Flyway、MyBatis-Plus、JWT key paths、server port
- **新增 application-dev.properties**：本機開發用，override key paths
- **新增 .gitignore**：排除 key/、logs/、target/、IDE files、.env
- **建立 package 骨架**：按 domain 分 package（common、user、store、order、transaction）
- **移除 FastJSON 依賴規劃**：改用 Spring Boot 內建 Jackson（原 spec 中 FastJSON 1.2.79 有 CVE 風險）

## Capabilities

### New Capabilities
- `docker-environment`: Docker Compose 開發環境配置（MySQL container、volume 掛載、log 配置）
- `project-config`: Maven dependencies、Spring Boot application properties、Spring Profiles 配置
- `project-structure`: Package 骨架與 .gitignore 設定

### Modified Capabilities
<!-- 無既有 capabilities 需要修改 -->

## Impact

- **Dependencies**: pom.xml 大幅新增依賴（security, mybatis-plus, jjwt, bouncy castle, testcontainers, validation, websocket）
- **Configuration**: 新增 application.properties、application-dev.properties、docker-compose.yml、my.cnf
- **Project structure**: 新增 domain package 目錄結構
- **Dev workflow**: 開發者需先 `docker compose up -d` 啟動 MySQL，再 `mvn spring-boot:run` 啟動 app
- **Key management**: JWT/RSA key 檔案位於專案外 `../key/`，不納入版控；dev profile 可 override 為本機路徑
