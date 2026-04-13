## Context

系統目前有 3 個 Controller（Auth、Order、User），使用 `@Slf4j` 在各 service 中零散記錄日誌，沒有統一的 request/response logging。排查問題時缺乏一致的存取記錄。

## Goals / Non-Goals

**Goals:**
- 所有 Controller 方法自動記錄 request 與 response 資訊
- 日誌格式一致：HTTP method、URI、參數、status、耗時
- 敏感欄位脫敏（password 相關欄位）
- 異常也能記錄

**Non-Goals:**
- 不做日誌持久化（不寫入 DB 或 ELK）
- 不做 distributed tracing（那是之後的事）
- 不攔截 WebSocket handler

## Decisions

### 1. 使用 `@Aspect` + `@Around` 攔截 Controller 層

**選擇**：Spring AOP `@Around("within(com.example.orderSystem.controller..*)")`

**替代方案**：
- `HandlerInterceptor`：能拿到 HttpServletRequest/Response，但拿不到方法參數和回傳值
- `Filter`：更底層，拿不到 Spring 層資訊

**理由**：`@Around` 能同時拿到方法參數、回傳值、執行時間、例外，最適合做完整的 API logging。

### 2. 日誌格式

```
[API] POST /order/create_order | userId=alice | args=[CreateOrderRequest(...)] | status=200 | 45ms
[API] GET /order/get_all_shops | userId=anonymous | args=[] | status=200 | 12ms
[API] POST /login/create_token | userId=anonymous | args=[MASKED] | status=200 | 120ms
[API-ERROR] POST /order/join_order | userId=bob | args=[...] | exception=InsufficientBalanceException: 餘額不足 | 8ms
```

- 正常請求用 INFO level
- 異常用 WARN level

### 3. 敏感欄位處理

對含有 `password`、`token`、`secret` 關鍵字的參數值，替換為 `[MASKED]`。
判斷邏輯：檢查方法參數名稱或參數類別的欄位名稱。

### 4. 取得 userId

從 `SecurityContextHolder` 取得當前認證使用者。未認證的 endpoint（如 login）顯示 `anonymous`。

### 5. Package 位置

新增 `com.example.orderSystem.aspect` package，放置 `ApiAccessLogAspect`。

## Risks / Trade-offs

- **[效能]** 每個 request 多一次 AOP 攔截 → 影響極小（< 1ms），可忽略
- **[日誌量]** 所有 API 都會記錄 → INFO level，production 可透過 logback 調整
- **[參數序列化]** 複雜物件 toString 可能很長 → 截斷超過 500 字元的參數
