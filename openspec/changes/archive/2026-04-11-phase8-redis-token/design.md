## Context

目前 token 機制：
- **Access Token**：JWT RS256，15 分鐘過期，純 stateless 驗簽（`JwtAuthenticationFilter`），logout 後仍可用直到過期
- **Refresh Token**：UUID 存在 MySQL `refresh_tokens` 表，logout 時設 `revoked=true`，每次 `/auth/refresh` 查 DB
- **Redis**：專案中完全沒有 Redis（無依賴、無 docker service、無配置）

此 change 負責：引入 Redis 基礎建設 + Access Token Blacklist + Refresh Token 遷移到 Redis。

## Goals / Non-Goals

**Goals:**
- 引入 Redis 基礎建設（docker-compose、Maven 依賴、Spring 配置），供此 change 及後續 phase 共用
- Access Token logout 即時失效（Redis blacklist）
- Refresh Token 改存 Redis（取代 MySQL）
- 所有既有測試通過（Testcontainers 加入 Redis）

**Non-Goals:**
- 不刪除 MySQL `refresh_tokens` 表（保留 schema，只是不再寫入）
- 不處理 DelayQueue 遷移（phase9）
- 不處理 WebSocket 跨實例（phase10）
- 不做 Redis cluster / sentinel（單機 Redis 即可）

## Decisions

### 1. Redis 基礎建設

**docker-compose.yml** 加入 Redis service：
```yaml
redis:
  image: redis:7-alpine
  container_name: paypool-redis
  restart: unless-stopped
  ports:
    - "6379:6379"
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 10
```

**pom.xml** 加入：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

**application-dev.properties** 加入：
```properties
spring.data.redis.host=localhost
spring.data.redis.port=6379
```

**Testcontainers**：測試用 `GenericContainer<>("redis:7-alpine")`，透過 `@DynamicPropertySource` 注入。

### 2. Access Token Blacklist

**機制**：

```
Login：
  JwtUtils.generateAccessToken() 加入 jti claim (UUID)
  → JWT 裡多了一個唯一 ID

Logout：
  1. 從 request 拿到當前 access token
  2. 解析出 jti 和剩餘存活時間
  3. Redis SET "blacklist:{jti}" "" EX <剩餘秒數>
  → token 過期後 Redis key 自動消失，不需清理

每次 Request（JwtAuthenticationFilter）：
  1. 解析 JWT 拿到 jti
  2. Redis EXISTS "blacklist:{jti}"
  3. 存在 → 拒絕（不設 authentication）
```

**Redis key 設計**：
| Key | Value | TTL |
|-----|-------|-----|
| `blacklist:{jti}` | `""` (空字串) | token 剩餘存活秒數（最多 900s） |

**為什麼用 jti 而不是整個 JWT？**
- jti 是 UUID (36 字元)，JWT 可能有幾百字元，省空間
- jti 是唯一的，不會碰撞

**logout endpoint 變更**：
```
現在：POST /login/logout  body: { "refreshToken": "xxx" }
改後：POST /login/logout  body: { "refreshToken": "xxx" }
      + Header: Authorization: Bearer <accessToken>（已經是 authenticated endpoint）
```
從 SecurityContext 或 request header 取得 access token，解析 jti 並加入 blacklist。同時也處理 refresh token 的失效。

### 3. Refresh Token 搬到 Redis

**Redis key 設計**：
| Key | Value | TTL |
|-----|-------|-----|
| `refresh:{tokenId}` | JSON: `{"userId":"xxx","createdAt":"..."}` | 7 天 |

**流程對比**：

| 操作 | 現在 (MySQL) | 改後 (Redis) |
|------|-------------|-------------|
| Login 建立 | `refreshTokenMapper.insert(token)` | `SET refresh:{id} {json} EX 604800` |
| Refresh 驗證 | `refreshTokenMapper.selectById(id)` + 檢查 revoked + 檢查 expireTime | `GET refresh:{id}`，不存在 = 失效 |
| Logout 失效 | `token.setRevoked(true)` + `updateById` | `DEL refresh:{id}` |

**簡化**：不再需要 `revoked` flag 和 `expireTime` 欄位 — Redis TTL 自動處理過期，DEL 直接刪除 = 失效。

### 4. 新增 `TokenRedisService`

抽出一個專門操作 Redis 的 service，封裝所有 token 相關的 Redis 操作：

```java
@Service
public class TokenRedisService {
    // Access Token Blacklist
    void blacklistAccessToken(String jti, long remainingSeconds);
    boolean isAccessTokenBlacklisted(String jti);

    // Refresh Token
    void saveRefreshToken(String tokenId, String userId, long ttlSeconds);
    String getRefreshTokenUserId(String tokenId);
    void deleteRefreshToken(String tokenId);
}
```

這樣 `AuthService` 和 `JwtAuthenticationFilter` 只依賴這個 service，不直接碰 `StringRedisTemplate`。也方便測試 mock。

### 5. JwtUtils 修改

`generateAccessToken` 加入 jti：
```java
return Jwts.builder()
        .id(UUID.randomUUID().toString())  // ← 新增
        .subject(userId)
        .claim("role", role)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(privateKey)
        .compact();
```

新增 helper：
```java
public String extractJti(String token) { ... }
public long getRemainingSeconds(Claims claims) { ... }
```

### 6. WebSocketConfig 中的 JWT 驗證

`WebSocketConfig` 的 CONNECT interceptor 也用 `jwtUtils.parseToken()`，blacklist 檢查需要同步加入。注入 `TokenRedisService`，CONNECT 時也檢查 jti 是否在 blacklist。

## Risks / Trade-offs

- **[可用性]** Redis 掛掉 → 所有認證請求失敗。緩解：目前單機部署，Redis 和 App 在同一台，風險可控。未來可加 Redis Sentinel。
- **[Refresh Token 持久性]** Redis 重啟會丟失所有 refresh token → 使用者需重新登入。緩解：開啟 Redis RDB persistence（預設已開）；7 天 TTL 本身就不是永久資料。
- **[每次 request 多一次 Redis 查詢]** Access token blacklist 檢查。緩解：Redis 單次 EXISTS 查詢 < 1ms，可忽略。
- **[logout 需要帶 access token]** 現在 logout 只傳 refreshToken，改後需要從 header 解析 access token。但 logout 本身就是 authenticated endpoint（需要 Bearer token），所以 header 本來就有。
