## 1. Redis 基礎建設

- [x] 1.1 `docker-compose.yml` 加入 Redis service（redis:7-alpine, port 6379, healthcheck）
- [x] 1.2 `pom.xml` 加入 `spring-boot-starter-data-redis` 依賴
- [x] 1.3 `application-dev.properties` 加入 `spring.data.redis.host` / `port` 設定
- [x] 1.4 `application-test.properties` 加入 Redis Testcontainers 預留設定（由 @DynamicPropertySource 覆蓋）
- [x] 1.5 驗證 `docker compose up -d` 能同時啟動 MySQL + Redis，且 `redis-cli ping` 回 PONG（需啟動 Docker Desktop）

## 2. TokenRedisService

- [x] 2.1 新增 `TokenRedisService`，注入 `StringRedisTemplate`
- [x] 2.2 實作 `blacklistAccessToken(jti, remainingSeconds)` — SET blacklist:{jti} + EX
- [x] 2.3 實作 `isAccessTokenBlacklisted(jti)` — EXISTS blacklist:{jti}
- [x] 2.4 實作 `saveRefreshToken(tokenId, userId, ttlSeconds)` — SET refresh:{tokenId} + EX
- [x] 2.5 實作 `getRefreshTokenUserId(tokenId)` — GET refresh:{tokenId}
- [x] 2.6 實作 `deleteRefreshToken(tokenId)` — DEL refresh:{tokenId}

## 3. JwtUtils 修改

- [x] 3.1 `generateAccessToken` 加入 `.id(UUID.randomUUID().toString())` (jti claim)
- [x] 3.2 新增 `extractJti(String token)` 方法
- [x] 3.3 新增 `getRemainingSeconds(Claims claims)` 方法（計算 token 剩餘存活秒數）

## 4. Access Token Blacklist

- [x] 4.1 `JwtAuthenticationFilter` 注入 `TokenRedisService`，解析 JWT 後檢查 `isAccessTokenBlacklisted(jti)`
- [x] 4.2 `WebSocketConfig` CONNECT interceptor 同步加入 blacklist 檢查
- [x] 4.3 `AuthService.logout()` 新增：從 request header 取得 access token jti，呼叫 `blacklistAccessToken`

## 5. Refresh Token 遷移到 Redis

- [x] 5.1 `AuthService.login()` 改為呼叫 `tokenRedisService.saveRefreshToken()`（取代 `refreshTokenMapper.insert`）
- [x] 5.2 `AuthService.refresh()` 改為呼叫 `tokenRedisService.getRefreshTokenUserId()`（取代 `refreshTokenMapper.selectById`）
- [x] 5.3 `AuthService.logout()` 改為呼叫 `tokenRedisService.deleteRefreshToken()`（取代 revoked flag 更新）
- [x] 5.4 移除 `AuthService` 對 `RefreshTokenMapper` 的依賴

## 6. 測試

- [x] 6.1 建立測試用 Redis Testcontainer 共用基底（`@DynamicPropertySource` 設定 `spring.data.redis.*`）— 所有 5 個測試類別 + 3 個新測試類別都已加入
- [x] 6.2 `TokenRedisService` 整合測試：blacklist 存取、refresh token CRUD、TTL 過期驗證
- [x] 6.3 `JwtUtils` 單元測試：驗證產出的 JWT 包含 jti claim
- [x] 6.4 `AuthControllerTest` 更新：加入 Redis Testcontainer，測試 logout 後 access token 立即失效
- [x] 6.5 `AuthControllerTest` 更新：測試 refresh token 正常流程（login → refresh → logout → refresh 失敗）
- [x] 6.6 `AuthServiceTest` 更新：改為 mock `TokenRedisService`，移除 `RefreshTokenMapper` mock
- [x] 6.7 執行 `./mvnw test` 確認所有測試通過（需啟動 Docker Desktop）
