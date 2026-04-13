## 1. Flyway V2 Migration SQL

- [x] 1.1 建立 `V2__schema_updates.sql`
- [x] 1.2 ALTER TABLE `users` ADD COLUMN `balance BIGINT NOT NULL DEFAULT 0` AFTER `role`
- [x] 1.3 回填 balance：從每個 user 最新一筆 transaction 的 closing_balance 寫入（無交易記錄者保持 0）
- [x] 1.4 ALTER TABLE `orders` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN'
- [x] 1.5 UPDATE orders：TINYINT 值轉換為 enum name（0→OPEN, 1→CLOSED, 2→SETTLED, -1→CANCELLED, -2→FAILED）
- [x] 1.6 ALTER TABLE `transactions` MODIFY COLUMN `type` VARCHAR(20) NOT NULL
- [x] 1.7 UPDATE transactions：TINYINT 值轉換為 enum name（1→DEBIT, 2→RECHARGE）
- [x] 1.8 DROP TABLE `token_blacklist`
- [x] 1.9 CREATE TABLE `refresh_tokens`（token_id UUID PK, user_id FK CASCADE, revoked, expire_time, created_at, idx_user_id, idx_expire_time）
- [x] 1.10 ALTER TABLE `transactions` ADD CONSTRAINT FK on `order_id` → `orders.order_id`（ON DELETE RESTRICT ON UPDATE CASCADE）

## 2. 驗證

- [x] 2.1 `docker compose up -d` + `mvn spring-boot:run`，確認 V2 migration 成功執行
- [x] 2.2 驗證 users.balance 回填正確（alice=4895, bob=4860, charlie=2895, admin=0）
- [x] 2.3 驗證 orders.status 轉換正確（ord-001='SETTLED', ord-002='OPEN'）
- [x] 2.4 驗證 transactions.type 轉換正確（type='DEBIT' 或 'RECHARGE'）
- [x] 2.5 驗證 refresh_tokens 表已建立，token_blacklist 已刪除
- [x] 2.6 驗證 transactions.order_id FK 生效（嘗試插入不存在的 order_id 應失敗）
