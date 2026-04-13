## 1. Docker Compose 環境

- [x] 1.1 建立 `docker-compose.yml`：MySQL 8.0 service，port 3306，database `paypool`，charset utf8mb4，named volume `mysql-data`，bind mount `./logs/mysql`
- [x] 1.2 建立 `docker/mysql/my.cnf`：啟用 error log + slow query log (threshold 1s)，關閉 general log
- [x] 1.3 驗證 `docker compose up -d` 可成功啟動 MySQL 並連線

## 2. Maven Dependencies

- [x] 2.1 補齊 pom.xml dependencies：spring-boot-starter-security, mybatis-plus-spring-boot3-starter, mysql-connector-j, jjwt (0.12.x api/impl/jackson), bcprov-jdk18on, spring-boot-starter-websocket, spring-boot-starter-validation
- [x] 2.2 新增 test scope dependencies：testcontainers (mysql + junit-jupiter)
- [x] 2.3 修正 pom.xml 結構問題（目前 flyway dependency 在 `</dependencies>` 外面）
- [x] 2.4 驗證 `mvn compile` 成功 *(Java 21 LTS)*

## 3. Application 配置

- [x] 3.1 配置 `application.properties`：MySQL connection (localhost:3306/paypool), Flyway, MyBatis-Plus mapper locations, server.port=8591, JWT key paths (../key/*)
- [x] 3.2 建立 `application-dev.properties`：override key paths 為 ./key/*
- [x] 3.3 建立最小化 Spring Security config（permit-all），避免預設 security 擋住所有 request

## 4. Project 結構

- [x] 4.1 建立 domain package 骨架：common (config, exception, security, util), user, store, order, transaction — 每個 domain 下建 controller/service/mapper/entity/dto sub-packages
- [x] 4.2 更新 `.gitignore`：排除 key/, logs/, target/, IDE files, .env, OS files
- [x] 4.3 移動 `OrderSystemApplication.java` 中加入 `@MapperScan` 指向 mapper base package

## 5. 端對端驗證

- [x] 5.1 啟動 MySQL container + Spring Boot app，確認 Flyway migration 成功執行
- [x] 5.2 確認 seed data 已寫入（Flyway V1 migration 包含 seed data，migration 成功即寫入）
