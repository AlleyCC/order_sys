# PayPool - 團購訂單管理系統

公司內部團購/合資訂單管理系統的後端 API 服務，提供使用者認證、訂單建立與管理、帳戶餘額管理、支付結算、即時通訊等功能。

## 技術棧

| 項目 | 說明 |
|------|------|
| 語言 | Java 25 |
| 框架 | Spring Boot 3.5.13 |
| 資料庫 | MySQL 8.0 (Docker) |
| 資料存取 | MyBatis-Plus 3.5.12 |
| 認證 | JWT RS256 (Access + Refresh Token) |
| 即時通訊 | WebSocket (STOMP + SockJS) |
| 排程 | Java DelayQueue |
| 測試 | JUnit 5 + Mockito + Testcontainers |

## 前置需求

- Java 25+
- Docker (MySQL container)
- RSA key 檔案放在 `./key/` 目錄下

## 快速啟動

```bash
# 1. 啟動 MySQL (port 3307)
docker compose up -d

# 2. 編譯 + 啟動 (預設 dev profile, Flyway 自動執行 migration)
./mvnw package -DskipTests
java -jar target/orderSystem-0.0.1-SNAPSHOT.jar

# App 啟動於 http://localhost:8591
```

### Flyway 指令

```bash
./mvnw flyway:info       # 查看 migration 狀態
./mvnw flyway:migrate    # 只跑 migration，不啟動 app
./mvnw flyway:repair     # 修復 checksum 不一致
```

## API 規格

完整 API 規格書請參考：[docs/SPEC.md](docs/SPEC.md)

## Database Schema

### ERD

```mermaid
erDiagram
    users ||--o{ orders : "created_by (RESTRICT)"
    users ||--o{ order_items : "user_id (RESTRICT)"
    users ||--o{ transactions : "user_id (RESTRICT)"
    users ||--o{ refresh_tokens : "user_id (CASCADE)"
    stores ||--o{ orders : "store_id (RESTRICT)"
    stores ||--o{ menus : "store_id (CASCADE)"
    orders ||--o{ order_items : "order_id (CASCADE)"
    orders ||--o{ transactions : "order_id (RESTRICT, nullable)"
    menus ||--o{ order_items : "menu_id (RESTRICT)"

    users {
        varchar user_id PK
        varchar user_name
        varchar password
        varchar role
        bigint balance
        datetime created_at
        datetime updated_at
    }
    stores {
        varchar store_id PK
        varchar store_name
        varchar phone
        varchar address
        int min_order_amount
        datetime created_at
        datetime updated_at
    }
    menus {
        int menu_id PK "AUTO_INCREMENT"
        varchar store_id FK
        varchar product_name
        int unit_price
        tinyint is_available
        datetime created_at
        datetime updated_at
    }
    orders {
        varchar order_id PK "UUID"
        varchar store_id FK
        varchar created_by FK
        varchar order_name
        varchar status "OPEN/CLOSED/SETTLED/CANCELLED/FAILED"
        datetime deadline
        datetime created_at
        datetime updated_at
    }
    order_items {
        int item_id PK "AUTO_INCREMENT"
        varchar order_id FK
        varchar user_id FK
        int menu_id FK
        varchar product_name "snapshot"
        int unit_price "snapshot"
        int quantity
        int subtotal "GENERATED"
        datetime created_at
        datetime updated_at
    }
    transactions {
        varchar transaction_id PK "UUID"
        varchar user_id FK
        varchar order_id FK "nullable"
        int amount
        int closing_balance
        varchar type "DEBIT/RECHARGE"
        varchar created_by
        datetime created_at
    }
    refresh_tokens {
        varchar token_id PK "UUID"
        varchar user_id FK
        tinyint revoked
        datetime expire_time
        datetime created_at
    }
```

### Tables

| Table | 說明 | PK | 重要欄位 |
|-------|------|----|---------|
| users | 使用者 | user_id (VARCHAR) | balance (BIGINT), role |
| stores | 店家 | store_id (VARCHAR) | min_order_amount |
| menus | 菜單 | menu_id (AUTO_INCREMENT) | unit_price, is_available |
| orders | 團購訂單 | order_id (UUID) | status (VARCHAR), deadline |
| order_items | 訂單品項 | item_id (AUTO_INCREMENT) | subtotal (GENERATED) |
| transactions | 交易紀錄 | transaction_id (UUID) | type (DEBIT/RECHARGE), closing_balance |
| refresh_tokens | Refresh Token | token_id (UUID) | revoked, expire_time |

### Enums

**OrderStatus**: `OPEN` → `CLOSED` → `SETTLED` / `FAILED` / `CANCELLED`

**TradeType**: `DEBIT` (扣款) / `RECHARGE` (儲值)

## 流程圖

### 認證流程 (Access + Refresh Token)

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server

    C->>S: POST /login/create_token<br/>Header: JWT {RSA加密密碼}<br/>Body: { userId }
    S-->>S: RSA 解密 → BCrypt 驗證
    S-->>C: { accessToken (15min), refreshToken (7d) }

    C->>S: API calls<br/>Authorization: Bearer {accessToken}
    S-->>S: 純 JWT 驗簽，不查 DB
    S-->>C: 200 OK

    Note over C,S: accessToken 過期

    C->>S: POST /auth/refresh<br/>{ refreshToken }
    S-->>S: 查 DB: 未 revoke + 未過期
    S-->>C: { accessToken (new) }

    C->>S: POST /login/logout<br/>{ refreshToken }
    S-->>S: revoked = true
    S-->>C: 200 登出成功
```

### 訂單生命週期

```mermaid
flowchart LR
    A["團主開團<br/>create_order<br/>status=OPEN"] --> B["會員下單/改單<br/>create_user_order<br/>檢查可用餘額<br/>WebSocket 推送餘額"]
    B --> C["deadline 到達<br/>DelayQueue 觸發<br/>status=CLOSED<br/>自動嘗試扣款"]
    C --> D["結算結果<br/>SETTLED / FAILED<br/>WebSocket 推送結果"]
```

### 訂單狀態流轉

```mermaid
stateDiagram-v2
    [*] --> OPEN : 建立訂單

    OPEN --> CANCELLED : 團主取消
    OPEN --> CLOSED : deadline 到達<br/>DelayQueue 觸發

    CLOSED --> SETTLED : 全員餘額足夠
    CLOSED --> FAILED : 有人餘額不足
    CLOSED --> CANCELLED : 團主取消

    FAILED --> SETTLED : pay_order 重試

    note right of OPEN
        deadline 前可進行：
        - create_user_order (下單)
        - delete_user_order (刪除品項)
        - 團購聊天 (WebSocket)
    end note
```

### 結算扣款流程 (CAS Pattern)

```mermaid
flowchart TD
    A[payOrder] --> B{訂單狀態?}
    B -->|SETTLED| X1[409 已結算]
    B -->|OPEN| X2[400 尚未截止]
    B -->|CLOSED / FAILED| C[依 user_id 分組<br/>加總 order_items.subtotal]

    C --> D[對每位使用者執行 CAS 扣款<br/>UPDATE users SET balance = balance - amount<br/>WHERE user_id = ? AND balance >= ?]

    D --> E{affected_rows?}
    E -->|= 1| F[扣款成功<br/>寫入 transactions]
    E -->|= 0| G[餘額不足<br/>ROLLBACK 所有扣款]

    F --> H{全部完成?}
    H -->|是| I[status = SETTLED]
    H -->|否| D

    G --> J[status = FAILED]
```

### WebSocket 即時通訊

```mermaid
flowchart LR
    WS["ws://host:8591/ws<br/>(STOMP + SockJS)"]

    WS --> B["/user/queue/balance<br/>餘額同步（當事人）"]
    WS --> N["/user/queue/notification<br/>結算通知（當事人）"]
    WS --> CH["/topic/order/{orderId}/chat<br/>團購聊天（同訂單所有人）"]
```

## 測試

```bash
./mvnw test                        # 全部測試
./mvnw test -Dtest=ClassName       # 單一 class
```

| 類型 | 工具 | 說明 |
|------|------|------|
| Service 單元測試 | JUnit 5 + Mockito | Mock mapper，驗證業務邏輯 |
| Controller 整合測試 | SpringBootTest + MockMvc + Testcontainers | 真實 MySQL，驗完整 HTTP 流程 |

## 專案結構

```
src/main/java/com/example/orderSystem/
├── config/          # Spring 配置 (Security, WebSocket, MyBatisPlus)
├── controller/      # REST API endpoints
├── service/         # 業務邏輯
├── mapper/          # MyBatis-Plus mappers
├── entity/          # 資料庫實體
├── dto/             # Request/Response DTOs
├── enums/           # OrderStatus, TradeType
├── exception/       # 全域例外處理 (RFC 7807)
├── scheduler/       # DelayQueue 自動結算
├── security/        # JWT Authentication Filter
├── websocket/       # 聊天 STOMP handler
└── util/            # JWT, RSA, BCrypt 工具

src/main/resources/
├── db/migration/    # Flyway migrations (V1, V2, V3)
├── mapper/          # MyBatis XML (JOIN queries)
└── application*.properties
```
