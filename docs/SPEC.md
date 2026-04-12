# PayPool - 團購訂單管理系統 API 規格書

## 1. 系統概述

PayPool 是一個團購/合資訂單管理系統的後端 API 服務，提供使用者認證、訂單建立與管理、帳戶餘額管理、支付結算等功能。

### 1.1 技術棧

| 項目 | 版本 / 說明 |
|------|------------|
| 語言 | Java 25 (LTS) |
| 框架 | Spring Boot 3.5.13 |
| 安全 | Spring Security (Stateless JWT) |
| 資料庫 | MySQL 8.0 (Docker) |
| 資料存取 | MyBatis-Plus 3.5.12 |
| JWT | JJWT 0.12.6 (RS256) |
| 密碼加密 | RSA (傳輸層) + BCrypt (儲存層) |
| JSON | Jackson (Spring Boot 內建) |
| 加密庫 | Bouncy Castle 1.80 (bcprov-jdk18on) |
| 即時通訊 | WebSocket (STOMP + SockJS) |
| 排程 | Java DelayQueue |
| 工具 | Lombok |
| 建構 | Maven |
| 測試 | JUnit 5 + Mockito + Testcontainers (MySQL) |
| 埠號 | 8591 |

### 1.2 系統架構

```
Client (REST + WebSocket)
  │
  ├── HTTP ──→ [Spring Security Filter Chain] ── JwtAuthenticationFilter
  │                │
  │                ▼
  │            [Controller] ── REST API 端點、@Valid 參數驗證
  │                │
  │                ▼
  │            [Service] ── 商業邏輯、@Transactional 事務管理
  │                │
  │                ▼
  │            [Mapper] ── MyBatis-Plus BaseMapper + 自訂 XML
  │                │
  │                ▼
  │            [MySQL 8.0] ── paypool 資料庫
  │
  └── WS ───→ [WebSocket (STOMP)] ── 即時訂單同步 / 聊天 / 通知
                   │
                   ▼
               [DelayQueue] ── deadline 到期觸發自動結算
```

---

## 2. 認證機制

### 2.1 登入流程

```
1. Client 將密碼以 RSA 公鑰加密
2. POST /login/create_token
   - Header: Authorization: JWT <RSA加密密碼>
   - Body: { "userId": "xxx" }
3. Server:
   a. RSA 私鑰解密密碼
   b. BCrypt 比對資料庫儲存的雜湊值
   c. 查詢使用者角色 (user_role)
   d. 簽發 Access Token (JWT RS256, 15min) + Refresh Token (UUID, 7d 存入 DB)
4. 回傳: { "accessToken": "<JWT>", "refreshToken": "<UUID>", "expiresIn": 900 }
```

### 2.2 Token 驗證

- 受保護的 API 需在 Header 帶上 `Authorization: Bearer <JWT>`
- `jwtAuthInterceptor` 執行以下檢查:
  1. Header 格式是否正確
  2. JWT 簽章是否有效 (RS256 公鑰驗證)
  3. Token 是否過期
- 驗證通過後，將 `userId` (JWT subject) 存入 `request.setAttribute("userId", ...)`
- Access Token 為短命 token (15 分鐘)，驗證時不查 DB（純 stateless JWT 驗簽）

### 2.3 Token 架構 (Access + Refresh)

系統採用雙 Token 架構：

| Token | 有效期 | 儲存位置 | 用途 |
|-------|--------|---------|------|
| Access Token | 15 分鐘 | Client memory / cookie | 呼叫 API，不查 DB |
| Refresh Token | 7 天 | httpOnly cookie | 換發新的 Access Token |

**Refresh 流程：**
1. Access Token 過期 → API 回傳 401
2. 前端用 Refresh Token 呼叫 `POST /auth/refresh`
3. Server 查 DB 確認 Refresh Token 有效且未 revoke
4. 簽發新 Access Token，使用者無感

### 2.4 登出機制

- `POST /login/logout` 將當前 Refresh Token 標記為 revoked（`refresh_tokens.revoked = 1`）
- 舊 Access Token 最多 15 分鐘後自然過期
- Refresh Token 被 revoke 後，無法再換發新 Access Token

### 2.5 受保護的 API 路徑

| 路徑 | 需要認證 |
|------|---------|
| `POST /login/create_token` | No |
| `POST /auth/refresh` | No |
| `POST /login/logout` | Yes |
| `GET /order/get_all_shops` | No |
| `GET /order/get_all_orders` | Yes |
| `GET /order/get_user_account` | Yes |
| `GET /order/get_order_detail` | Yes |
| `POST /order/create_order` | Yes |
| `POST /order/create_user_order` | Yes |
| `POST /order/delete_user_order` | Yes |
| `POST /order/cancel_order` | Yes |
| `POST /order/pay_order` | Yes |
| `GET /user/get_user_transaction_record` | Yes |
| `WS /ws` | Yes (STOMP 連線時驗證 JWT) |

### 2.6 金鑰檔案

金鑰檔案存放於專案目錄外，與專案同層的 `./key/` 資料夾下，不納入版本控制。

```
parent-directory/
├── paypool/              ← 專案根目錄
│   ├── src/
│   ├── pom.xml
│   └── ...
└── key/                  ← 金鑰目錄 (專案外)
    ├── private_key.pem
    ├── public_key.pem
    ├── password_private.key
    └── password_public.key
```

| 檔案 | 用途 |
|------|------|
| `../key/private_key.pem` | JWT 簽章 (RS256 Private Key) |
| `../key/public_key.pem` | JWT 驗證 (RS256 Public Key) |
| `../key/password_private.key` | 登入密碼 RSA 解密 |
| `../key/password_public.key` | 登入密碼 RSA 加密 (Client 端使用) |

金鑰路徑透過 `application.properties` 設定：

```properties
app.jwt.private-key=../key/private_key.pem
app.jwt.public-key=../key/public_key.pem
app.password.private-key=../key/password_private.key
app.password.public-key=../key/password_public.key
```

---

## 3. API 規格

### 3.1 回應格式

**成功 (2xx)：** 直接回傳資料，不額外包裝。

```json
// 回傳物件
{ "orderId": "ord-001", "orderName": "午餐鍋貼團", ... }

// 回傳陣列
[{ "storeId": "store001", "storeName": "八方雲集(烏日店)" }, ...]

// 僅訊息
{ "message": "扣款成功" }
```

**失敗 (4xx/5xx)：** 採用 RFC 7807 Problem Details 格式，由 `@ControllerAdvice` + Spring Boot 內建 `ProblemDetail` 統一處理。

```json
{ "status": 400, "detail": "必須輸入 orderId" }
```

| 欄位 | 型別 | 說明 |
|------|------|------|
| `status` | int | HTTP 狀態碼 |
| `detail` | String | 具體錯誤訊息 |

> 注：實際回應中 Spring Boot 會自動附帶 `type`、`title`、`instance` 欄位（RFC 7807 規範），文件中省略。

---

### 3.2 認證 API

#### POST `/login/create_token`

登入取得 JWT Token。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | 格式: `JWT <RSA加密後的密碼>` |
| Body | userId | String | Y | 使用者帳號 |

**Response 200 OK:**

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "expiresIn": 900
}
```

**Response 401 Unauthorized:**

```json
{ "status": 401, "detail": "帳密錯誤" }
```

---

#### POST `/auth/refresh`

使用 Refresh Token 換發新的 Access Token。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Body | refreshToken | String | Y | Refresh Token UUID |

**Response 200 OK:**

```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...(新的)",
  "expiresIn": 900
}
```

**Response 401 Unauthorized:**

```json
{ "status": 401, "detail": "Refresh Token 已失效" }
```

---

#### POST `/login/logout`

登出，撤銷 Refresh Token。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | 格式: `Bearer <JWT>` |
| Body | refreshToken | String | Y | 要撤銷的 Refresh Token UUID |

**Response 200 OK:**

```json
{ "message": "登出成功" }
```

---

### 3.3 訂單 API

#### GET `/order/get_all_shops`

取得所有店家列表。

**Request:** 無參數

**Response 200 OK:**

```json
[
  {
    "storeId": "store001",
    "storeName": "八方雲集(烏日店)",
    "minOrderAmount": 350
  }
]
```

---

#### GET `/order/get_all_orders`

取得所有訂單列表。

**Request:** 無參數

**Response 200 OK:**

```json
[
  {
    "orderId": "ord-001",
    "orderName": "午餐鍋貼團",
    "deadline": "2025-04-10T12:00:00",
    "minOrderAmount": 350
  }
]
```

---

#### GET `/order/get_user_account`

查詢使用者帳戶餘額。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Query | userId | String | Y | 使用者帳號 |

**Response 200 OK:**

```json
{
  "balance": 5000,
  "availableBalance": 4840
}
```

**Response 400 Bad Request:**

```json
{ "status": 400, "detail": "必須輸入 userId" }
```

---

#### GET `/order/get_order_detail`

取得訂單明細 (含所有使用者的訂購品項)。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Query | orderId | String | Y | 訂單 ID (UUID) |

**Response 200 OK:**

```json
{
  "orderId": "ord-002",
  "orderName": "下午茶飲料團",
  "minOrderAmount": 250,
  "deadline": "2025-04-15 15:00:00",
  "status": "OPEN",
  "createdBy": "bob",
  "createdAt": "2025-04-15 13:00:00",
  "orderItems": [
    {
      "itemId": 6,
      "userId": "alice",
      "userName": "Alice Chen",
      "productName": "珍珠奶茶(大)",
      "unitPrice": 55,
      "quantity": 1,
      "subtotal": 55
    },
    {
      "itemId": 7,
      "userId": "bob",
      "userName": "Bob Wang",
      "productName": "芒果綠茶(大)",
      "unitPrice": 60,
      "quantity": 2,
      "subtotal": 120
    }
  ]
}
```

**Response 404 Not Found:**

```json
{ "status": 404, "detail": "訂單不存在" }
```

---

#### POST `/order/create_order`

建立新的團購訂單。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | `Bearer <JWT>` |
| Body | storeId | String | Y | 店家 ID |
| Body | orderName | String | Y | 訂單名稱 |
| Body | deadline | String | Y | 截止時間，格式: `yyyy-MM-dd HH:mm:ss` |

**Response 201 Created:**

```json
{ "orderId": "550e8400-...", "storeId": "store001" }
```

**Response 404 Not Found:**

```json
{ "status": 404, "detail": "店家不存在" }
```

**業務邏輯:**
1. 驗證 storeId 是否存在於 `stores`
2. 產生 UUID 作為 `order_id`
3. 初始狀態 `status = 'OPEN'`
4. 建立者為 JWT 中的 userId
5. 將 deadline 排入 DelayQueue，到期自動觸發結算

---

#### POST `/order/create_user_order`

使用者在訂單中新增品項。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | `Bearer <JWT>` |
| Body | orderId | String | Y | 訂單 ID |
| Body | menuId | int | Y | 菜單項目 ID (FK → menus) |
| Body | quantity | int | Y | 數量 |

**Response 201 Created:**

```json
{ "message": "下單成功" }
```

**Response 404 Not Found / 400 Bad Request:**

```json
{ "status": 404, "detail": "該筆訂單不存在" }
{ "status": 400, "detail": "餘額不足" }
```

**業務邏輯:**
1. 確認訂單存在且狀態為 OPEN
2. 確認訂單未過截止時間 (`deadline`)，已過期則拒絕
3. 查詢 `menus` 取得 `product_name` 和 `unit_price`，驗證品項存在且供應中
4. 計算使用者可用餘額 = 最新帳戶餘額 - 所有 OPEN 及 FAILED 訂單的未結金額
5. 餘額不足則拒絕
6. 寫入 `order_items`（含品名與單價快照）
7. 同一使用者可重複點相同品項（例如多訂幾份）
8. 透過 WebSocket 推送當事人的可用餘額更新

---

#### POST `/order/delete_user_order`

刪除訂單品項或整筆訂單。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | `Bearer <JWT>` |
| Body | orderId | String | Y | 訂單 ID |
| Body | itemId | String/int | Y | 品項 ID，傳 `"all"` 則刪除整筆訂單 |

**Response 200 OK:**

```json
{ "message": "刪除成功" }
```

**Response 403 Forbidden:**

```json
{ "status": 403, "detail": "無權限刪除此品項" }
```

**權限規則:**
- **admin**: 可刪除任何訂單中的任何品項
- **開團者** (orders.created_by): 可刪除自己開的團中所有品項
- **一般使用者**: 只能刪除自己的品項

**業務邏輯:**
- 僅限訂單狀態為 OPEN 時可刪除
- `itemId = "all"`: 訂單狀態改為 CANCELLED，同時從 DelayQueue 移除排程
- `itemId = <id>`: 刪除單筆 `order_items`
- 透過 WebSocket 推送當事人的可用餘額更新

---

#### POST `/order/cancel_order`

團主取消訂單（OPEN 或 CLOSED 狀態）。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | `Bearer <JWT>` |
| Body | orderId | String | Y | 訂單 ID |

**Response 200 OK:**

```json
{ "message": "訂單已取消" }
```

**Response 403 / 400:**

```json
{ "status": 403, "detail": "僅開團者或 admin 可取消訂單" }
{ "status": 400, "detail": "僅 OPEN 或 CLOSED 狀態的訂單可取消" }
```

**業務邏輯:**
1. 確認當前用戶為開團者或 admin
2. 確認訂單狀態為 OPEN 或 CLOSED
3. 更新狀態 → CANCELLED
4. 若狀態為 OPEN，從 DelayQueue 移除排程任務
5. 透過 WebSocket 通知同訂單所有用戶

---

#### POST `/order/pay_order`

手動結算訂單（用於 FAILED 狀態重試）。正常流程由 DelayQueue 自動觸發結算。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | `Bearer <JWT>` |
| Body | orderId | String | Y | 訂單 ID |

**Response 200 OK:**

```json
{ "message": "扣款成功" }
```

**Response 404 Not Found / 400 Bad Request / 409 Conflict:**

```json
{ "status": 404, "detail": "訂單不存在" }
{ "status": 409, "detail": "該訂單已結算，無法進行付款" }
{ "status": 400, "detail": "alice 餘額不足" }
```

**業務邏輯 (��用 `@Transactional`):**
1. 確認訂單存在且狀態為 CLOSED 或 FAILED（SETTLED 回傳 409，OPEN 回傳 400）
2. 依 `order_items` 按 `user_id` 分組，加總每人 `subtotal`
3. 逐一對每位使用者執行 CAS 扣款：`UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?`
4. `affected_rows = 1` → 扣款成功，讀取新 balance，寫入 `transactions` (type='DEBIT')
5. `affected_rows = 0` → 餘額不足，狀態更新為 FAILED，rollback 所有扣款
6. 全部成功 → 狀態更新��� SETTLED
7. 透過 WebSocket 推送扣款結果通知 (`/user/queue/notification`) 及餘額更新 (`/user/queue/balance`) 給相關用戶

---

### 3.4 使用者 API

#### GET `/user/get_user_transaction_record`

查詢當前登入使用者的交易紀錄。

**Request:**

| 位置 | 欄位 | 型別 | 必填 | 說明 |
|------|------|------|------|------|
| Header | Authorization | String | Y | `Bearer <JWT>` |

**Response 200 OK:**

```json
[
  {
    "transactionId": "txn-init-alice",
    "amount": 5000,
    "createdAt": "2025-04-01 09:00:00"
  },
  {
    "transactionId": "txn-ord001-alice",
    "amount": -105,
    "createdAt": "2025-04-10 12:30:00"
  }
]
```

**業務邏輯:**
- `amount` 正數 = 儲值 (type='RECHARGE')
- `amount` 負數 = 扣款 (type='DEBIT')

---

## 4. 資料庫設計

資料庫版本控制使用 Flyway，migration 檔案位於 `src/main/resources/db/migration/`。

### 4.1 ER 關聯圖

```
users (1) ──┬──< orders (N)          [created_by → user_id]
            ├──< order_items (N)     [user_id → user_id]
            ├──< transactions (N)    [user_id → user_id]
            └──< refresh_tokens (N)  [user_id → user_id, CASCADE]

stores (1) ──┬──< orders (N)         [store_id → store_id]
             └──< menus (N)          [store_id → store_id]

orders (1) ──┬──< order_items (N)    [order_id → order_id]
             └──< transactions (N)   [order_id → order_id, nullable, RESTRICT]

menus (1) ──< order_items (N)        [menu_id → menu_id]
```

### 4.2 資料表定義

#### users - 使用者

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| user_id | VARCHAR(20) | Y | Y | 使用者帳號 |
| user_name | VARCHAR(20) | | Y | 使用者名稱 |
| password | VARCHAR(256) | | Y | BCrypt 雜湊密碼 |
| role | VARCHAR(10) | | Y | 角色: `employee`, `admin` |
| balance | BIGINT | | Y | 帳戶餘額 (DEFAULT 0)，扣款使用 CAS 模式 |
| created_at | DATETIME | | Y | 建立時間 (DEFAULT CURRENT_TIMESTAMP) |
| updated_at | DATETIME | | Y | 更新時間 (ON UPDATE CURRENT_TIMESTAMP) |

#### stores - 店家

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| store_id | VARCHAR(20) | Y | Y | 店家 ID |
| store_name | VARCHAR(50) | | Y | 店家名稱 |
| phone | VARCHAR(20) | | | 電話 |
| address | VARCHAR(100) | | | 地址 |
| min_order_amount | INT | | Y | 最低訂購金額 |
| created_at | DATETIME | | Y | 建立時間 |
| updated_at | DATETIME | | Y | 更新時間 |

#### menus - 店家菜單

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| menu_id | INT AUTO_INCREMENT | Y | Y | 菜單項目 ID |
| store_id | VARCHAR(20) | | Y | FK → stores |
| product_name | VARCHAR(50) | | Y | 商品名稱 |
| unit_price | INT | | Y | 單價 |
| is_available | TINYINT(1) | | Y | 是否供應中: 1=是, 0=否 |
| created_at | DATETIME | | Y | 建立時間 |
| updated_at | DATETIME | | Y | 更新時間 |

#### orders - 團購訂單

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| order_id | VARCHAR(36) | Y | Y | UUID |
| store_id | VARCHAR(20) | | Y | FK → stores |
| created_by | VARCHAR(20) | | Y | FK → users，開團者 |
| order_name | VARCHAR(45) | | Y | 訂單名稱 |
| status | VARCHAR(20) | | Y | OPEN, CLOSED, SETTLED, CANCELLED, FAILED (DEFAULT 'OPEN') |
| deadline | DATETIME | | Y | 訂單截止時間 |
| created_at | DATETIME | | Y | 建立時間 |
| updated_at | DATETIME | | Y | 更新時間 |

訂單總金額不再儲存於欄位中，改由 `SUM(order_items.subtotal)` 即時計算。

#### order_items - 訂單品項

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| item_id | INT AUTO_INCREMENT | Y | Y | 品項 ID |
| order_id | VARCHAR(36) | | Y | FK → orders |
| user_id | VARCHAR(20) | | Y | FK → users，點餐者 |
| menu_id | INT | | Y | FK → menus，關聯菜單項目 |
| product_name | VARCHAR(50) | | Y | 下單當下的品名快照 |
| unit_price | INT | | Y | 下單當下的單價快照 |
| quantity | INT | | Y | 數量 (預設 1) |
| subtotal | INT (GENERATED) | | Y | 小計，自動計算 `unit_price * quantity` |
| created_at | DATETIME | | Y | 建立時間 |
| updated_at | DATETIME | | Y | 更新時間 |

設計要點:
- `menu_id` 確保下單品項必須來自菜單，防止任意輸入
- `product_name` + `unit_price` 為下單當下的快照，菜單改價不影響歷史訂單
- `subtotal` 為 STORED generated column，由資料庫自動計算

#### transactions - 交易紀錄

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| transaction_id | VARCHAR(36) | Y | Y | UUID |
| user_id | VARCHAR(20) | | Y | FK → users (RESTRICT) |
| order_id | VARCHAR(36) | | | FK → orders (RESTRICT)，nullable，儲值時為 NULL |
| amount | INT | | Y | 交易金額 (正數) |
| closing_balance | INT | | Y | 交易後餘額 |
| type | VARCHAR(20) | | Y | DEBIT(扣款), RECHARGE(儲值) |
| created_at | DATETIME | | Y | 交易時間 |
| created_by | VARCHAR(20) | | Y | 操作者 |

索引: `(user_id, created_at DESC)` 複合索引，加速交易紀錄查詢。

#### refresh_tokens - Refresh Token

| 欄位 | 型別 | PK | NOT NULL | 說明 |
|------|------|----|----------|------|
| token_id | VARCHAR(36) | Y | Y | UUID |
| user_id | VARCHAR(20) | | Y | FK → users (CASCADE) |
| revoked | TINYINT(1) | | Y | 0=有效, 1=已撤銷 (DEFAULT 0) |
| expire_time | DATETIME | | Y | 過期時間 (7 天) |
| created_at | DATETIME | | Y | 建立時間 |

索引: `idx_user_id (user_id)`、`idx_expire_time (expire_time)`

---

## 5. 列舉定義

### OrderStatus

| 名稱 | 說明 | 可下單/改單 | 可取消 | 可扣款 |
|------|------|:---------:|:-----:|:-----:|
| OPEN | 開團中（deadline 前） | O | O | X |
| CLOSED | 已截止（deadline 到達） | X | O | O |
| SETTLED | 已結算（扣款完成） | X | X | X |
| CANCELLED | 已取消（團主主動取消） | X | X | X |
| FAILED | 結算失敗（餘額不足） | X | X | O (可重試) |

**狀態流轉：**

```
OPEN ──→ CLOSED ──→ SETTLED
  │         │
  │         ├─→ FAILED ──→ SETTLED (餘額補足後重試)
  │         │
  │         └─→ CANCELLED (截止後團主仍可取消)
  │
  └──→ CANCELLED (開團中團主主動取消)
```

| 轉換 | 觸發方式 |
|------|---------|
| OPEN → CLOSED | DelayQueue 在 deadline 到達時自動觸發 |
| OPEN → CANCELLED | 團主呼叫取消訂單 API 或 delete_user_order itemId="all" |
| CLOSED → SETTLED | 自動結算（DelayQueue 觸發 CLOSED 後立即嘗試扣款） |
| CLOSED → FAILED | 自動結算時有使用者餘額不足 |
| CLOSED → CANCELLED | 團主截止後仍可取消訂單 |
| FAILED → SETTLED | 手動呼叫 pay_order API 重試 |

### TradeType

| 名稱 | 說明 |
|------|------|
| DEBIT | 扣款 |
| RECHARGE | 儲值 |

---

## 6. 專案結構

```
src/main/java/com/example/orderSystem/
├── OrderSystemApplication.java          # @SpringBootApplication + @MapperScan
├── aspect/
│   └── ApiAccessLogAspect.java          # AOP API 存取日誌 (切面)
├── config/
│   ├── MyBatisPlusConfig.java           # MyBatis-Plus 設定 (分頁插件等)
│   ├── SecurityConfig.java              # Spring Security + JWT Filter 設定
│   └── WebSocketConfig.java             # WebSocket STOMP 設定
├── security/
│   └── JwtAuthenticationFilter.java     # JWT 驗證 Filter (OncePerRequestFilter)
├── controller/
│   ├── AuthController.java              # 認證 API (login/logout/refresh)
│   ├── OrderController.java             # 訂單 API
│   └── UserController.java              # 使用者 API
├── service/
│   ├── AuthService.java                 # 認證業務邏輯
│   ├── NotificationService.java         # WebSocket 通知推送
│   ├── OrderService.java                # 訂單業務邏輯
│   ├── PaymentService.java              # 結算扣款邏輯
│   ├── TokenRedisService.java           # Redis Token 黑名單管理
│   └── UserService.java                 # 使用者業務邏輯
├── mapper/
│   ├── UserMapper.java                  # extends BaseMapper<User> + 自訂 XML
│   ├── StoreMapper.java                 # extends BaseMapper<Store>
│   ├── MenuMapper.java                  # extends BaseMapper<Menu>
│   ├── OrderMapper.java                 # extends BaseMapper<Order> + 自訂 XML
│   ├── OrderItemMapper.java             # extends BaseMapper<OrderItem> + 自訂 XML
│   ├── TransactionMapper.java           # extends BaseMapper<Transaction>
│   └── RefreshTokenMapper.java          # extends BaseMapper<RefreshToken>
├── entity/
│   ├── User.java
│   ├── Store.java
│   ├── Menu.java
│   ├── Order.java
│   ├── OrderItem.java
│   ├── Transaction.java
│   └── RefreshToken.java
├── dto/
│   ├── request/                         # 請求 DTO (@Valid)
│   │   ├── LoginRequest.java
│   │   ├── RefreshRequest.java
│   │   ├── CreateOrderRequest.java
│   │   ├── CreateOrderItemRequest.java
│   │   └── DeleteOrderItemRequest.java
│   ├── response/                        # 回應 DTO
│   │   ├── LoginResponse.java
│   │   ├── RefreshResponse.java
│   │   ├── OrderDetailResponse.java
│   │   ├── OrderItemResponse.java
│   │   └── TransactionResponse.java
│   └── websocket/                       # WebSocket 訊息 DTO
│       ├── BalanceMessage.java
│       ├── ChatMessage.java
│       └── SettlementMessage.java
├── enums/
│   ├── OrderStatus.java
│   └── TradeType.java
├── exception/
│   ├── GlobalExceptionHandler.java      # @ControllerAdvice
│   ├── AuthenticationException.java
│   ├── ConflictException.java
│   ├── ResourceNotFoundException.java
│   ├── InsufficientBalanceException.java
│   └── ForbiddenException.java
├── scheduler/
│   ├── OrderSettlementTask.java         # DelayQueue 任務 (implements Delayed)
│   ├── OrderSettlementConsumer.java     # 背景執行緒，消費到期任務
│   └── OrderSettlementQueue.java        # DelayQueue 管理 (新增/移除/重啟恢復)
├── websocket/
│   └── ChatController.java              # 團購聊天室 (STOMP handler)
└── util/
    ├── JwtUtils.java                    # JWT 簽發/驗證
    ├── BcryptUtils.java                 # BCrypt 雜湊
    └── PasswordUtils.java              # RSA 金鑰載入

src/main/resources/
├── application.properties               # 應用設定 (含金鑰路徑)
├── application-dev.properties           # 開發環境設定
├── mapper/                              # MyBatis XML Mapper
│   ├── UserMapper.xml                   # 使用者相關查詢
│   ├── OrderMapper.xml                  # 複雜 JOIN 查詢
│   └── OrderItemMapper.xml              # 品項相關查詢
└── db/migration/
    ├── V1__init_schema.sql              # Flyway 初始 Schema + Seed Data
    ├── V2__schema_updates.sql           # balance, refresh_tokens, status/type VARCHAR, FK
    └── V3__fix_seed_passwords.sql       # 修復種子資料密碼

src/test/java/com/example/orderSystem/   # 測試目錄
├── aspect/                              # AOP 測試
├── controller/
│   ├── AuthControllerTest.java
│   ├── OrderControllerTest.java
│   └── UserControllerTest.java
├── service/
│   └── OrderSettleIntegrationTest.java
└── resources/
    └── application-test.properties

../key/                                  # 金鑰目錄 (專案外，不納入版控)
├── private_key.pem
├── public_key.pem
├── password_private.key
└── password_public.key
```

---

## 7. 核心業務流程

### 7.1 團購訂單生命週期

```
[團主開團]        [會員下單/改單]       [deadline 到達]       [結算結果]
create_order  →  create_user_order  →  DelayQueue 觸發  →  扣款成功/失敗
status=OPEN      檢查餘額                status=CLOSED       SETTLED / FAILED
                 即時同步(WS)            自動嘗試扣款
                                        通知用戶(WS)
```

**完整狀態流轉：**

```
                  團主取消
            ┌───────────────→ CANCELLED
            │                     ↑
 建立訂單 → OPEN ──── deadline 到達 ──→ CLOSED ──→ 自動扣款
            │    (DelayQueue 觸發)         │
            │                              ├── 全員餘額足夠 → SETTLED ✓
            │                              ├── 有人餘額不足 → FAILED
            │                              │                   │
            │                              │ pay_order 重試 ───┘
            │                              └── 團主取消 → CANCELLED
            │
            │  deadline 前可進行：
            ├── create_user_order (下單，WS 即時同步)
            ├── delete_user_order (刪除品項)
            └── 團購聊天 (WS)
```

### 7.2 帳戶餘額定義

系統中有兩種餘額，用途不同：

| 名稱 | 定義 | 儲存方式 | 何時變動 |
|------|------|---------|---------|
| **實際餘額** (`balance`) | 帳戶餘額 | `users.balance` 欄位 | 儲值、扣款成功時（CAS UPDATE） |
| **可用餘額** (`available_balance`) | 實際餘額扣除尚未結算的凍結金額 | 不存 DB，即時計算 | 下單、刪除品項、結算時 |

**可用餘額計算公式：**

```sql
available_balance = users.balance - SUM(OPEN 和 FAILED 訂單中該使用者的 order_items.subtotal)
```

**使用場景：**

| 場景 | 用哪個 |
|------|--------|
| 下單時檢查餘額是否足夠 | 可用餘額 |
| 結算扣款時 CAS 更新 users.balance | 實際餘額 |
| WebSocket 推送餘額更新 | 可用餘額 |
| 查詢帳戶餘額 (`get_user_account`) | 回傳兩者 |

> FAILED 訂單的金額也要凍結，因為這些訂單尚未扣款但仍可能重試結算。

**`get_user_account` 回應格式：**

```json
{
  "balance": 5000,
  "availableBalance": 4840
}
```

### 7.3 自動結算流程 (DelayQueue + @Transactional)

```
1. 建單時：將 (orderId, deadline) 放入 DelayQueue
2. 背景執行緒阻塞等待 DelayQueue
3. Deadline 到達，任務彈出：
   a. 更新訂單狀態 OPEN → CLOSED
   b. 按 user_id 分組加總 order_items.subtotal
   c. 逐一對每位使用者執行 CAS 扣款：UPDATE users SET balance = balance - ? WHERE user_id = ? AND balance >= ?
   d. affected_rows = 1 → 成功，讀取新 balance，寫入 transactions (type='DEBIT')
   e. 全部成功 → 狀態 → SETTLED
   f. 任一 affected_rows = 0 → 餘額不足，狀態 → FAILED，rollback 所有扣款
4. 透過 WebSocket 推送結算結果給所有相關用戶
5. 應用重啟時：從 DB 撈 status=OPEN 的訂單，重新放入 DelayQueue
```

### 7.4 WebSocket 即時通訊

**連線端點：** `ws://host:8591/ws`（STOMP over SockJS）

**頻道設計：**

| 頻道 (Topic) | 用途 | 推送對象 | 觸發時機 |
|-------------|------|---------|---------|
| `/user/queue/balance` | 帳戶餘額同步 | 當事人 | 下單、改單、刪除品項、扣款後 |
| `/user/queue/notification` | 系統通知 | 當事人 | 扣款結果、訂單狀態變更 |
| `/topic/order/{orderId}/chat` | 團購聊天室 | 同訂單所有人 | 用戶發送訊息 |

> 訂單品項的異動（新增/刪除）不廣播給所有人，僅推送當事人的可用餘額更新。訂單明細由前端呼叫 `GET /order/get_order_detail` 主動查詢。

**餘額同步訊息格式：**

```json
{
  "type": "BALANCE_UPDATED",
  "availableBalance": 4840,
  "reason": "下單：珍珠奶茶(大) x1"
}
```

**扣款通知訊息格式：**

```json
{
  "type": "SETTLEMENT",
  "orderId": "ord-002",
  "orderName": "下午茶飲料團",
  "result": "SETTLED",
  "amount": -55,
  "balance": 4840
}
```

**結算失敗通知：**

```json
{
  "type": "SETTLEMENT",
  "orderId": "ord-002",
  "orderName": "下午茶飲料團",
  "result": "FAILED",
  "detail": "餘額不足，請儲值後聯繫團主重新結算"
}
```

**聊天訊息格式：**

```json
{
  "type": "CHAT",
  "orderId": "ord-002",
  "userId": "alice",
  "userName": "Alice Chen",
  "message": "有人要加點嗎？湊個 $250 免運",
  "timestamp": "2025-04-15 14:30:00"
}
```

---

## 8. 測試規格

### 8.1 測試架構

| 層級 | 工具 | 資料庫 | 說明 |
|------|------|--------|------|
| Service 單元測試 | JUnit 5 + Mockito | Mock Mapper | 驗證業務邏輯，不碰 DB |
| Mapper 整合測試 | `@MybatisPlusTest` + Testcontainers | MySQL (Testcontainers) | 驗證 SQL 及 BaseMapper 操作，使用真實 MySQL |
| Controller 整合測試 | `@SpringBootTest` + MockMvc + Testcontainers | MySQL (Testcontainers) | 驗證 API 端點、認證、回應格式 |

### 8.2 認證模組測試

#### LoginService

| 測試案例 | 預期結果 |
|---------|---------|
| 正確帳號密碼登入 | 回傳 accessToken + refreshToken |
| 密碼錯誤 | 401，detail: "密碼錯誤" |
| 帳號不存在 | 401，detail: "密碼錯誤"（不揭露帳號是否存在） |
| 登出 | 200，Refresh Token 標記為 revoked |
| 使用已 revoke 的 Refresh Token 換發 | 401 |
| 登出後舊 Access Token 在 15 分鐘內仍可使用 | 200（空窗期，設計如此） |
| Token 過期 | 401，detail: "Token Expired" |
| Authorization Header 缺失 | 401 |
| Authorization Header 格式錯誤 (非 Bearer) | 401 |

### 8.3 訂單模組測試

#### create_order

| 測試案例 | 預期結果 |
|---------|---------|
| 正常建立訂單 | 201，回傳 orderId + storeId，任務進入 DelayQueue |
| storeId 不存在 | 404 |
| 缺少必填欄位 (orderName / deadline) | 400 |
| deadline 在過去 | 400 |
| 未帶 Token | 401 |

#### create_user_order

| 測試案例 | 預期結果 |
|---------|---------|
| 正常下單 (status=OPEN) | 201，品項寫入 order_items，WebSocket 廣播更新 |
| 訂單不存在 | 404 |
| 訂單已截止 (status=CLOSED) | 400 |
| 訂單已結算 (status=SETTLED) | 400 |
| 訂單已取消 (status=CANCELLED) | 400 |
| menuId 不存在 | 404 |
| 菜單品項已下架 (is_available=0) | 400 |
| 餘額不足 | 400，detail: "餘額不足" |
| 同一使用者重複點相同品項 | 201，新增一筆 order_items（允許） |
| quantity <= 0 | 400 |

#### delete_user_order

| 測試案例 | 預期結果 |
|---------|---------|
| 一般使用者刪除自己的品項 (OPEN) | 200，WebSocket 廣播更新 |
| 一般使用者刪除別人的品項 | 403 |
| 開團者刪除團內任意品項 | 200 |
| 開團者刪除別人開的團的品項 | 403 |
| admin 刪除任何品項 | 200 |
| itemId="all"，開團者刪除整筆訂單 | 200，狀態 → CANCELLED，DelayQueue 任務移除 |
| itemId="all"，非開團者且非 admin | 403 |
| 訂單非 OPEN 狀態時刪除 | 400 |

#### get_order_detail

| 測試案例 | 預期結果 |
|---------|---------|
| 正常查詢 | 200，回傳訂單資訊 + orderItems 列表 + createdAt |
| orderId 不存在 | 404 |
| 訂單無品項 | 200，orderItems 為空陣列 |

#### pay_order (手動重試)

| 測試案例 | 預期結果 |
|---------|---------|
| FAILED 狀態，餘額已補足 | 200，狀態 → SETTLED，WebSocket 推送通知 |
| CLOSED 狀態，正常扣款 | 200，狀態 → SETTLED |
| 訂單不存在 | 404 |
| 訂單已結算 (status=SETTLED) | 409，detail: "該訂單已結算，無法進行付款" |
| 訂單仍開團中 (status=OPEN) | 400，detail: "訂單尚未截止" |
| 訂單已取消 (status=CANCELLED) | 400 |
| 其中一位使用者餘額不足 | 400，狀態 → FAILED，不扣任何人 |
| 訂單內無品項 | 400 |
| 扣款後驗證 users.balance 正確 | balance = 原餘額 - SUM(subtotal) |
| 扣款後驗證訂單狀態 = SETTLED | status = 'SETTLED' |

### 8.4 使用者模組測試

#### get_user_transaction_record

| 測試案例 | 預期結果 |
|---------|---------|
| 有交易紀錄的使用者 | 200，RECHARGE 為正數，DEBIT 為負數 |
| 無交易紀錄的使用者 | 200，空陣列 |
| 未帶 Token | 401 |

#### get_user_account

| 測試案例 | 預期結果 |
|---------|---------|
| 有交易紀錄的使用者 | 200，回傳 balance + availableBalance |
| 無交易紀錄的使用者 | 200，balance = 0, availableBalance = 0 |
| 缺少 userId 參數 | 400 |

### 8.5 排程模組測試

#### OrderSettlementQueue

| 測試案例 | 預期結果 |
|---------|---------|
| 建單後任務進入 DelayQueue | 任務存在且 delay 時間正確 |
| Deadline 到達，任務自動彈出 | 觸發結算，狀態 OPEN → CLOSED → SETTLED |
| Deadline 到達，有人餘額不足 | 狀態 OPEN → CLOSED → FAILED |
| 刪除訂單後，任務從 DelayQueue 移除 | 不再觸發結算 |
| 應用重啟後恢復 | DB 中 status=OPEN 的訂單重新放入 DelayQueue |
| 應用重啟後，已過期的 OPEN 訂單 | 立即觸發結算 |

### 8.6 WebSocket 測試

| 測試案例 | 預期結果 |
|---------|---------|
| 用戶下單後收到餘額更新 | `/user/queue/balance` 收到 BALANCE_UPDATED，availableBalance 正確 |
| 用戶刪除品項後收到餘額更新 | `/user/queue/balance` 收到 BALANCE_UPDATED，餘額回升 |
| 扣款成功後通知 | `/user/queue/notification` 收到 SETTLEMENT (result=SETTLED) |
| 扣款失敗後通知 | `/user/queue/notification` 收到 SETTLEMENT (result=FAILED) |
| 其他用戶下單不會收到通知 | 非當事人不收到 BALANCE_UPDATED |
| 聊天訊息發送與接收 | `/topic/order/{orderId}/chat` 收到 CHAT |
| 未認證的 WebSocket 連線 | 拒絕連線 |

### 8.7 Mapper 整合測試重點

| Mapper | 重點驗證 |
|--------|---------|
| OrderMapper | `getOrderDetail` 的 JOIN 查詢回傳結構正確 |
| OrderMapper | `getUserAvailableBalance` 計算邏輯：users.balance - OPEN 及 FAILED 訂單金額 |
| OrderMapper | `insertOrderItems` 寫入後 subtotal (generated column) 自動計算 |
| OrderMapper | `selectByStatus` 查詢指定狀態的訂單 (重啟恢復用) |
| UserMapper | `casDebit` CAS 扣款：UPDATE balance WHERE balance >= amount |
| TransactionMapper | 批次 insert 多筆 transaction 紀錄 |
