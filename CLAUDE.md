# PayPool - 團購訂單管理系統

## Dev Commands

```bash
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

If migration fails (checksum mismatch or schema drift), do the full reset from Dev Commands, then `./mvnw flyway:migrate`.

## Project Structure

- `docs/SPEC.md` — API spec + DB schema + business rules
- `openspec/` — Change proposals, designs, specs, tasks

## Development Rules

- **TDD**: 先寫測試（RED）→ 實作讓測試通過（GREEN）→ 重構（REFACTOR）
  - Controller 整合測試：`@SpringBootTest` + `MockMvc` + `Testcontainers`（測完整 HTTP 流程）
  - Service 單元測試：`JUnit 5` + `Mockito`（mock mapper，測業務邏輯複雜時才需要）
  - Mapper 整合測試：`@MybatisPlusTest` + `Testcontainers`（測複雜 SQL / XML query）

## Conventions

- DB enums stored as VARCHAR (e.g., OrderStatus: OPEN, CLOSED, SETTLED, CANCELLED, FAILED)
- Balance operations use CAS pattern: `UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?`
- JWT: Access Token (15min) carries identity only (`sub` + `jti`), never roles — authorization is looked up per request by `DynamicAuthorizationManager`. Refresh Token (7d) lives in Redis (`refresh:{tokenId}`). Logout = put the access token's `jti` on the Redis blacklist (TTL = its remaining lifetime) + delete the refresh token
- All monetary amounts are integers (not decimal)
