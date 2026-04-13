# PayPool - 團購訂單管理系統

## Tech Stack

Java 25, Spring Boot 3.5.13, MySQL 8.0, MyBatis-Plus, JJWT (RS256), Testcontainers

## Dev Commands

```bash
# Build
./mvnw clean compile

# Start MySQL (port 3307)
docker compose up -d

# Run app (port 8591, runs Flyway migration automatically)
./mvnw package -DskipTests && java -jar target/orderSystem-0.0.1-SNAPSHOT.jar
# Stop: Ctrl+C

# Full reset (wipe DB + re-run all migrations)
docker compose down -v && docker compose up -d
# Wait for MySQL healthy, then:
./mvnw spring-boot:run
```

## DB Access

```bash
docker exec paypool-mysql mysql -uroot -proot1234 paypool -e "YOUR_SQL_HERE"
```

## Flyway Commands

```bash
./mvnw flyway:info       # 查看 migration 狀態
./mvnw flyway:migrate    # 只跑 migration，不啟動 app
./mvnw flyway:repair     # 修復 checksum 不一致
./mvnw flyway:clean      # 清空整個 DB schema（危險！等同 drop all tables）
```

## Verify Migration

After modifying Flyway SQL, run this sequence to verify:

```bash
# 1. Ensure MySQL is running
docker compose up -d

# 2. Run migration only
./mvnw flyway:migrate

# 3. Verify with SQL queries as needed
docker exec paypool-mysql mysql -uroot -proot1234 paypool -e "SHOW TABLES;"
```

If migration fails (checksum mismatch or schema drift), full reset:

```bash
docker compose down -v && docker compose up -d
# Wait ~5s for MySQL to be ready
./mvnw flyway:migrate
```

## Project Structure

- `src/main/resources/db/migration/` — Flyway migrations (V1, V2, ...)
- `src/main/java/com/example/orderSystem/` — Application code
- `docs/SPEC.md` — API spec + DB schema + business rules
- `openspec/` — Change proposals, designs, specs, tasks

## Development Rules

- **TDD**: 先寫測試（RED）→ 實作讓測試通過（GREEN）→ 重構（REFACTOR）
  - Controller 整合測試：`@SpringBootTest` + `MockMvc` + `Testcontainers`（測完整 HTTP 流程）
  - Service 單元測試：`JUnit 5` + `Mockito`（mock mapper，測業務邏輯複雜時才需要）
  - Mapper 整合測試：`@MybatisPlusTest` + `Testcontainers`（測複雜 SQL / XML query）
- 測試執行：`./mvnw test` (全部) 或 `./mvnw test -Dtest=ClassName` (單一)

## Conventions

- DB enums stored as VARCHAR (e.g., OrderStatus: OPEN, CLOSED, SETTLED, CANCELLED, FAILED)
- Balance operations use CAS pattern: `UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?`
- JWT: Access Token (15min, stateless) + Refresh Token (7d, DB-backed)
- All monetary amounts are integers (not decimal)
