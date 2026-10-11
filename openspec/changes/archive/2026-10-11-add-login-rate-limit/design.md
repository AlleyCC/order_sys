# Design: add-login-rate-limit

## Context

動機見 proposal.md - Why;行為約定見 `specs/login-rate-limit`。

與本設計相關的現況:

- `AuthService.login` 依序做 RSA 解密 → `selectById(userId)` → BCrypt 比對;任何一步失敗都丟 `AuthenticationException("帳密錯誤")`,由 `GlobalExceptionHandler` 轉成 401
- `LoginRequest.userId` 只有 `@NotBlank`,沒有長度限制;`users.user_id` 是 `VARCHAR(20)`,collation `utf8mb4_0900_ai_ci`(**不分大小寫**),所以 `Alice` 與 `alice` 會查到同一個使用者
- Redis 目前只透過 Spring Data Redis(Lettuce)存取:`TokenRedisService`、`RbacCacheService`、`NotificationPublisher`、`RedisSettlementQueue`(含 4 支 Lua)
- 既有的 Redis 故障策略:token 黑名單檢查 fail-open(`TokenAuthenticator`)
- 應用程式前面沒有反向代理,`docker-compose.yml` 只有 MySQL 與 Redis
- 測試:`AbstractIntegrationTest` 以 singleton 容器共用一個 Redis;`AuthControllerTest` 自帶容器、對同一帳號從 `127.0.0.1` 登入多次

## Goals / Non-Goals

**Goals:**

- 被擋的請求在 RSA 解密與 BCrypt 之前就結束,不消耗密碼驗證的運算
- 三層限流集中在一個元件,`AuthService` 只呼叫「可以嘗試嗎」與「登入成功了」兩個動作
- 既有 Redis 程式碼與既有測試行為不受影響

**Non-Goals:**

- 通用的 `@RateLimiter` 註解(ruoyi 的做法)——目前只有登入一個使用點,等第二個端點需要時再抽
- IPv6 前綴聚合(見 Risks)

## Decisions

### D1. Redisson 與 Spring Data Redis 並存,手動建立 `RedissonClient`

只加入 `org.redisson:redisson`,自行宣告 `RedissonClient` bean,連線位址讀取既有的 `spring.data.redis.host` / `port`(/ `password`,若有)。

- **不用 `redisson-spring-boot-starter`**:starter 會以 Redisson 實作取代 Spring 的 `RedisConnectionFactory`,既有的 `StringRedisTemplate`、Pub/Sub 訂閱、結算佇列的 Lua 都會換到新的 client 上,影響範圍與本變更無關
- **沿用 `spring.data.redis.*`**:測試的 `@DynamicPropertySource` 已經設定這組屬性,Redisson 自動連到 Testcontainers 的 Redis,不用另外注入
- 替代方案:全部改用 `StringRedisTemplate` + 自寫 Lua 滑動窗口。可行,但滑動窗口與 key 過期都要自己寫與測;`RRateLimiter` 已經是現成且經過驗證的實作

### D2. 用 `RRateLimiter` 實作三層計數

每一層是一個 `RRateLimiter`(`RateType.OVERALL`,全叢集共用):

| 層 | key | 預設 rate / interval |
|---|---|---|
| L1 來源 IP | `login:limit:ip:{rate}-{秒}s:{ip}` | 5 / 1 分鐘 |
| L2 帳號 + IP | `login:limit:user-ip:{rate}-{秒}s:{userId}:{ip}` | 5 / 15 分鐘 |
| L3 帳號全域 | `login:limit:user:{rate}-{秒}s:{userId}` | 20 / 1 小時 |

`RRateLimiter` 以 sorted set 記錄每次取得許可的時間,超過 interval 的許可才釋放,行為上是**滑動時間窗**,沒有固定窗口交界處兩倍流量的問題(實作時以測試確認,見 tasks 2.x)。

每個 limiter 的 key MUST 設定過期時間(≥ interval)。攻擊者可以送大量不同的 userId 或從大量 IP 來,沒有過期的 key 會無限累積在 Redis。

門檻放在 `app.login-limit.*` 設定,用 `@ConfigurationProperties` 綁定。`trySetRate` 只在 key 不存在時生效,而每次嘗試都會延長 key 的存活時間,持續有人登入的 IP 其 key 永遠不會過期、永遠是舊門檻。所以 key 名稱帶上限與時間窗(例如 `login:limit:ip:5-60s:1.2.3.4`):改設定後自然改用新 key,舊 key 沒人用就過期。

### D3. 先取許可再驗證,而不是「失敗才計數」

```
✗ 失敗才計數:檢查剩餘次數 → 驗證密碼 → 失敗才扣一次
   攻擊者同時送 1000 個請求,全部都在「檢查」時看到還有剩,全部進入 BCrypt
✓ 先扣再驗證:tryAcquire → 失敗即拒絕;成功才驗證密碼
   併發請求在 Redis 端原子扣減,最多只有 rate 個請求進入密碼驗證
```

所以 L1/L2/L3 計的是「嘗試次數」。對 L2 而言,登入成功後刪除該 limiter,效果等同「只計失敗」:帳號本人打錯 4 次、第 5 次成功,計數歸零。

L3 **不**因登入成功而歸零:若歸零,攻擊者累積的計數會因帳號本人登入而被清掉,攻擊者可以繼續猜。L3 只隨時間窗滑動恢復。

### D4. 判斷順序

```
login(userId, ip)
  │
  ├─ L1  tryAcquire(ip)                    ✗ → 401
  ├─ key = normalize(userId)
  ├─ L2  tryAcquire(key, ip)               ✗ → 401
  ├─ trusted = ip ∈ 受信任IP(key)
  ├─ if !trusted: L3 tryAcquire(key)       ✗ → 401
  │
  ├─ RSA 解密 → selectById → BCrypt        ✗ → 401
  │
  └─ 成功:刪除 L2(key, ip);ip 加入受信任IP(key),TTL 30 天(重設)
```

- 受信任 IP 只豁免 L3,仍受 L1、L2 限制
- 受信任 IP 的嘗試不消耗 L3 許可,帳號本人自己打錯密碼不會把自己的全域額度用完
- 前一層拒絕時,後面的層不扣許可

### D5. 受信任 IP 紀錄

`login:trusted:{userId}` 用 Redisson `RSetCache<String>`,成員是 IP,每個成員各自帶 30 天 TTL;登入成功時以 `add(ip, 30, DAYS)` 寫入或延長。

- 只有登入成功才能寫入,攻擊者沒有密碼就無法把自己的 IP 變成受信任
- 替代方案:`StringRedisTemplate` 的 ZSET(score = 最後成功時間)再自行清理過期成員。`RSetCache` 已經處理逐成員過期,且與 D2 同一個 client

### D6. 帳號 key 正規化與長度

- key 用請求帶的 `userId`,`trim()` 後 `toLowerCase(Locale.ROOT)`;對應 DB 不分大小寫的 collation,避免以大小寫變化繞過 L2/L3
- `LoginRequest.userId` 新增 `@Size(max = 20)`,與 `users.user_id` 一致。超過長度的帳號不可能存在,直接回 400,同時避免攻擊者用超長字串塞爆 Redis key。400 只說明「格式不合法」,不透露帳號是否存在
- collation 的 `ai` 是不分重音:`alicé`、`ålice` 在 DB 都查到 alice,但轉小寫後仍是不同的限流 key,攻擊者每換一種寫法就多一組額度。`LoginRequest.userId` 新增 `@Pattern(^[A-Za-z0-9._-]*$)`,非 ASCII 一律 400。現有帳號都是英數字,不受影響
  - 替代方案:限流前先查 DB,用正式帳號當 key。可行,但每次嘗試多一次查詢,且之後新增帳號規則時仍要記得兩邊一致;直接限制字元較簡單

### D7. 被擋時的回應

直接丟 `AuthenticationException("帳密錯誤")`,沿用既有的 401 處理,回應與帳密錯誤完全相同,不帶 `Retry-After`。

### D8. 來源 IP

`AuthController` 以 `HttpServletRequest.getRemoteAddr()` 取得,傳入 `AuthService.login`。不讀 `X-Forwarded-For`。日後加上 nginx 時,改設定 `server.forward-headers-strategy` 與可信代理,`getRemoteAddr()` 就會回傳代理轉送的真實 IP,限流程式不用改。

### D9. Redis 故障時 fail-open

限流元件捕捉 Redisson 的連線例外,記錄 `warn` log 後視為「允許」;登入成功後的歸零與寫入受信任 IP 失敗也只記 log。與 token 黑名單的 fail-open 一致。

Redisson 的預設是逾時 3 秒、重試 4 次,Redis 變慢時一個請求要等 15～20 秒才會丟出例外、才輪到 fail-open。所以限流專用的 Redisson 改為指令逾時 300ms(`app.login-limit.redis-timeout`)、不重試:Redis 一變慢,最多 0.3 秒就放行。連線逾時另外設定為 2 秒(`app.login-limit.redis-connect-timeout`):連線只在第一次建立、之後重複使用,放寬不會拖慢平常的登入;若與指令逾時共用 300ms,遠端或 TLS 的 Redis 交握較慢時連線永遠建不起來,限流會一直 fail-open 而形同失效。這個值不沿用 `spring.data.redis.timeout`,因為其他功能(結算、token)要的是「等久一點也要成功」,限流要的是「等不到就放行」。

適用的是**部分故障**:Redisson 那條連線逾時或重連中,但 Spring Data Redis 那條仍正常。Redis 整台掛掉時,登入成功後寫入 Refresh Token 一樣會失敗,fail-open 救不了,也不需要救。反過來選 fail-closed,攻擊者只要讓 Redis 變慢就能讓所有人登不進去。

### D10. 測試隔離

- 測試共用同一個 Redis,且 MockMvc 預設來源都是 `127.0.0.1`。`application-test.properties` 把三層門檻調高,避免既有測試(`AuthControllerTest` 對同一帳號登入多次)被限流
- 限流測試另以 `@TestPropertySource` 設定門檻,`@BeforeEach` 刪除 `login:*` key,並以 `request.setRemoteAddr(...)` 模擬不同 IP
- L1 與 L2 的上限都是 5 次,同一 IP 對同一帳號的第 6 次請求兩層都會拒絕,測不出是哪一層擋的。所以拆成兩個測試類別:L1 測試用預設門檻;L2 / L3 測試把 L1 調高(例如每分鐘 1000 次),只讓待測的那一層生效

## Risks / Trade-offs

- **[被擋的回應比較快,可由時間差分辨]** 被擋的請求不做 BCrypt,回應時間明顯較短,攻擊者可以從回應時間判斷自己被限流。→ 接受。為了隱藏時間差而對被擋請求也跑 BCrypt,等於讓攻擊者照樣消耗 CPU,違背 Goals 第一條;攻擊者知道被擋並不幫助他猜到密碼
- **[帳號本人從新 IP 登入可能被擋]** L3 觸發期間,帳號本人換到沒用過的網路(手機熱點、出差)會被擋。→ 這是選擇「受信任 IP 豁免」的代價;最長等 1 小時。日後可加「寄驗證信解鎖」
- **[正常使用者也看不到要等多久]** 回 401 不帶 `Retry-After`。→ 接受,為了不讓攻擊者分辨
- **[IPv6 可輕易換位址]** 一個 IPv6 使用者通常擁有整個 /64,可以換數不完的位址繞過 L1/L2。→ 本次不處理;L3 仍會擋住分散式猜同一帳號。日後可將 IPv6 聚合到 /64 前綴再計數
- **[NAT 後多人共用 IP]** 公司或學校共用出口 IP,L1(每分鐘 5 次)由所有人共用,上班時段同時登入可能誤擋。→ 接受,被擋的使用者最多等 1 分鐘;若實際誤擋頻繁,調高 `app.login-limit` 的 L1 門檻即可,不用改程式
- **[撞庫仍擋不住]** 攻擊者以大量 IP、每組外洩帳密只試一次,三層都不會觸發。→ 不在範圍內,需要 MFA 或異常偵測
- **[`RRateLimiter` 的實作細節依版本而異]** 滑動窗口行為與 key 過期 API 在不同 Redisson 版本有差異。→ 鎖定版本,並以測試驗證「時間窗內超量被拒、key 有 TTL」

## Migration Plan

- 無資料庫 migration
- 部署即生效;回滾只需移除限流呼叫,殘留的 `login:*` key 會依 TTL 自然過期
