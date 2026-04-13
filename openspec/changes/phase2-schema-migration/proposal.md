## Why

Phase 1 建立了可運行的開發環境，但 schema 在幾次 design review 後發現需要調整：

1. **餘額查詢效能差** — 目前沒有 `users.balance`，每次查餘額要掃 transactions 表找最新 closing_balance
2. **JWT 登出機制不完整** — `token_blacklist` 用 JWT 全文當 PK (VARCHAR 768)，效能差且不支援 refresh token 流程
3. **order status 可讀性差** — 用 TINYINT magic number（0, 1, -1...），直接看 DB 無法理解狀態含義
4. **transactions.order_id 缺少 FK** — 遺漏了 foreign key constraint，無法保證 order_id 參照完整性
5. **DELETED 狀態多餘** — 已決定不需要 soft delete status，取消訂單只需 CANCELLED

## What Changes

- **users 表新增 `balance`**：`BIGINT NOT NULL DEFAULT 0`，並從現有 transactions 回填餘額
- **刪除 `token_blacklist`**：改為 `refresh_tokens` 表，PK 用 UUID (VARCHAR 36)，支援 access + refresh 雙 token 架構
- **orders.status 改型態**：`TINYINT` → `VARCHAR(20)`，存 enum name（OPEN, CLOSED, SETTLED, CANCELLED, FAILED）
- **移除 DELETED 狀態**：status 只保留 OPEN / CLOSED / SETTLED / CANCELLED / FAILED
- **transactions.order_id 加 FK**：nullable，ON DELETE RESTRICT ON UPDATE CASCADE
- **transactions.type 改型態**：`TINYINT` → `VARCHAR(20)`，存 enum name（DEBIT, RECHARGE，未來可加 REFUND）

## Capabilities

### New Capabilities
- `user-balance`: users 表新增 balance 欄位，支援 CAS (Compare-And-Set) 扣款模式
- `refresh-tokens`: refresh_tokens 表，支援 access + refresh 雙 token JWT 架構

### Modified Capabilities
- `order-status-enum`: orders.status 從 TINYINT 改為 VARCHAR，移除 DELETED 狀態
- `transaction-fk`: transactions.order_id 新增 FK constraint
- `transaction-type-enum`: transactions.type 從 TINYINT 改為 VARCHAR

## Impact

- **Migration**: 新增 `V2__schema_updates.sql`，包含 ALTER TABLE、資料轉換、DROP/CREATE TABLE
- **Seed data**: V1 的 seed data 中 order status 為 TINYINT，V2 會轉換為 VARCHAR
- **Java code**: 後續 Phase 需配合新的 enum 定義與 refresh token 邏輯（不在此 Phase scope）
- **Breaking change**: `token_blacklist` 被刪除，任何依賴此表的程式碼需改用 `refresh_tokens`（目前無業務代碼依賴）
