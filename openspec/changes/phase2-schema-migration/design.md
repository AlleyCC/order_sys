## Context

Phase 1 建立了 PayPool 的開發環境與初始 schema (V1)。經過 design review，決定在進入 API 開發前先修正 schema 設計問題。所有變更透過 Flyway V2 migration 執行。

技術棧：Java 25、Spring Boot 3.5.13、MySQL 8.0、MyBatis-Plus、JJWT (RS256)。

## Goals / Non-Goals

**Goals:**
- V2 migration 可在現有 V1 schema + seed data 上成功執行
- 執行後 users.balance 與 transactions 記錄一致
- order status 從 TINYINT 完整轉換為 VARCHAR enum name
- refresh_tokens 表結構就緒，供後續 API 開發使用

**Non-Goals:**
- 不實作 API 或業務邏輯
- 不實作 refresh token 的簽發/驗證流程（後續 Phase）
- 不寫 Java entity 或 mapper（後續 Phase）

## Decisions

### 1. 餘額存在 users 表，交易操作用 CAS 模式

**選擇**: users 新增 `balance BIGINT NOT NULL DEFAULT 0`，扣款時用 `UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?` (Compare-And-Set)。

**理由**: 查餘額只需讀 users 一張表，不用每次 JOIN transactions。CAS UPDATE 在 InnoDB 中天然 atomic（UPDATE 自動加 exclusive row lock），不需要額外的 SELECT FOR UPDATE，鎖持有時間最短。

**替代方案**:
- 悲觀鎖 (SELECT FOR UPDATE) → 安全但鎖持有時間更長，多一條 SQL
- 樂觀鎖 (version 欄位) → 需要重試邏輯，過度設計

### 2. Refresh Token 取代 Token Blacklist

**選擇**: 刪除 `token_blacklist`，新建 `refresh_tokens` 表。採用 access token (短命 15min) + refresh token (長命 7d) 架構。

**理由**:
- Access token 驗證不查 DB（純 JWT 簽章驗證），保留 stateless 優勢
- 登出時 revoke refresh token（DB 一筆 UPDATE），舊 access token 最多 15 分鐘自然過期
- PK 從 VARCHAR(768) 的 JWT 全文改為 VARCHAR(36) 的 UUID，index 效能大幅改善

**空窗期可接受**: PayPool 是公司內部團購系統，15 分鐘空窗期風險極低。

### 3. Order Status 用 VARCHAR 存 Enum Name

**選擇**: `status` 欄位從 `TINYINT` 改為 `VARCHAR(20)`，存 Java enum 的 name（OPEN, CLOSED, SETTLED, CANCELLED, FAILED）。

**理由**: 直接看 DB 就能理解狀態，debug 方便。MyBatis-Plus `@EnumValue` 搭配 String enum 零轉換成本。表不會有百萬筆，VARCHAR 的效能差異可忽略。

### 4. 移除 DELETED 狀態

**選擇**: Order status 只保留 5 種：OPEN, CLOSED, SETTLED, CANCELLED, FAILED。

**理由**: Soft delete 應該用 `deleted_at` 欄位實現（如果需要的話），不應佔用業務狀態碼。目前不需要 soft delete，取消訂單用 CANCELLED 即可。

**狀態流轉**:
```
OPEN → CLOSED → SETTLED
OPEN → CLOSED → FAILED
OPEN → CLOSED → CANCELLED  (截止後團主仍可取消)
OPEN → CANCELLED            (開團中團主主動取消)
```

### 5. transactions.order_id 加 FK，保持 nullable

**選擇**: 新增 FK constraint，ON DELETE RESTRICT ON UPDATE CASCADE，欄位維持 nullable。

**理由**: 儲值時 order_id = NULL（FK 自動忽略 NULL 值），扣款/退款時 order_id 必須指向真實 order。RESTRICT 防止有交易記錄的 order 被意外刪除。

### 6. Transaction Type 用 VARCHAR 存 Enum Name

**選擇**: `transactions.type` 從 `TINYINT` 改為 `VARCHAR(20)`，存 enum name（DEBIT, RECHARGE）。

**理由**: 與 order status 保持一致性。直接看 DB 即可理解交易類型，不用對照 1=DEBIT, 2=RECHARGE。未來新增 REFUND 時只需加 enum 值，不用記下一個數字。

### 7. balance 使用 BIGINT

**選擇**: `users.balance` 用 `BIGINT` 而非 `INT`。

**理由**: 預留未來以分為單位計算金額的可能性（如 70.5 元 → 7050）。BIGINT 多佔 4 bytes（8 vs 4），對 users 表幾乎無影響，但避免未來改型態需再跑一次 migration。

## Risks / Trade-offs

- **V2 migration 不可逆** — ALTER TABLE + DROP TABLE 無法自動 rollback。Mitigation: 開發環境可 `docker compose down -v` 重建。
- **Status 轉換依賴 V1 seed data** — 如果 V1 seed data 有非預期的 status 值，UPDATE CASE 會漏轉。Mitigation: V1 只有 0 和 2 兩種 status，已在 CASE 中覆蓋。
- **balance 回填依賴 transactions 資料正確性** — 如果 closing_balance 有錯，回填的 balance 也會錯。Mitigation: seed data 已手動驗算正確。
