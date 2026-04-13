## Why

目前 Access Token (JWT 15min) logout 後仍可使用直到過期，沒有即時失效機制。Refresh Token 存在 MySQL `refresh_tokens` 表，每次 refresh 都查 DB。在多實例部署場景下，需要一個所有實例共享的、高速的 token 狀態存儲。

Redis 是最適合的選擇：記憶體級速度、原生 TTL 支持（過期自動清除）、所有實例共享。

## What Changes

### 基礎建設（此 change 負責，供後續 phase 共用）
- `docker-compose.yml` 加入 Redis service
- `pom.xml` 加入 `spring-boot-starter-data-redis`
- `application.properties` 加入 Redis 連線設定
- 新增 `RedisConfig` 配置類

### Access Token Blacklist
- Logout 時將 JWT 的 jti（token ID）存入 Redis，TTL = token 剩餘存活時間
- `JwtAuthenticationFilter` 每次驗證時多查 Redis：jti 在 blacklist 裡 → 拒絕
- `JwtUtils.generateAccessToken()` 加入 jti claim

### Refresh Token 搬到 Redis
- 新的 refresh token 存入 Redis（key: `refresh:{tokenId}`, value: userId + metadata, TTL: 7 天）
- Logout 時直接刪除 Redis key（不再是 revoked flag）
- Refresh 時查 Redis 取代查 MySQL
- 移除 `RefreshTokenMapper`、`RefreshToken` entity 的使用（保留 DB 表但不再寫入）

## Capabilities

### New Capabilities
- `redis-infrastructure`: Redis 基礎建設（docker、依賴、配置），供所有 phase 共用
- `access-token-blacklist`: Access Token 即時失效機制（Redis-backed）

### Modified Capabilities
- `auth-refresh`: Refresh Token 從 MySQL 遷移到 Redis

## Impact

- 新增依賴：`spring-boot-starter-data-redis`
- 新增基礎設施：Redis container (docker-compose)
- `JwtAuthenticationFilter`：新增 blacklist 檢查
- `JwtUtils`：generateAccessToken 加入 jti
- `AuthService`：login/logout/refresh 全部改用 Redis
- 測試：Testcontainers 需加入 Redis container
- **不影響**：其他 REST API、Order/User Service、WebSocket
