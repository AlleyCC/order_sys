## Why

Phase 2 完成了 schema migration，DB 結構就緒。現在需要實作認證模組，這是所有其他 API 的前置依賴 — 沒有 auth 就無法驗證使用者身份。

## What Changes

- **Entity**: User, RefreshToken
- **Mapper**: UserMapper, RefreshTokenMapper
- **DTO**: LoginRequest, LoginResponse, RefreshRequest, RefreshResponse
- **Service**: AuthService (登入、登出、refresh token)
- **Controller**: AuthController (3 個 endpoint)
- **Security**: JwtAuthenticationFilter (OncePerRequestFilter)、SecurityConfig 改為實際權限控制
- **Util**: JwtUtils (RS256 簽發/驗證)、PasswordUtils (RSA 解密)
- **Exception**: GlobalExceptionHandler (RFC 7807)

## Capabilities

### New Capabilities
- `auth-login`: POST /login/create_token — RSA 解密密碼 + BCrypt 驗證 + 簽發雙 token
- `auth-refresh`: POST /auth/refresh — Refresh Token 換發 Access Token
- `auth-logout`: POST /login/logout — Revoke Refresh Token
- `jwt-filter`: JwtAuthenticationFilter — stateless JWT 驗證，保護 API 路徑

## Impact

- SecurityConfig 從 permit-all 改為實際路徑權限控制
- 所有後續 API 可以依賴 JWT filter 取得 userId
- 需要 RSA key 檔案才能啟動（../key/ 目錄）
