## 1. Entity + Mapper

- [x] 1.1 User entity + UserMapper
- [x] 1.2 RefreshToken entity + RefreshTokenMapper

## 2. Util

- [x] 2.1 PasswordUtils — RSA key 載入 + 密碼解密
- [x] 2.2 JwtUtils — RS256 Access Token 簽發/驗證

## 3. Security

- [x] 3.1 JwtAuthenticationFilter (OncePerRequestFilter)
- [x] 3.2 SecurityConfig — 實際路徑權限控制 + JWT filter 掛載

## 4. DTO + Exception

- [x] 4.1 Request/Response DTOs (LoginRequest, LoginResponse, RefreshRequest, RefreshResponse)
- [x] 4.2 GlobalExceptionHandler (@ControllerAdvice, RFC 7807 ProblemDetail)

## 5. Service + Controller

- [x] 5.1 AuthService — 登入、登出、refresh
- [x] 5.2 AuthController — 3 個 endpoint

## 6. 測試

- [x] 6.1 AuthControllerTest — 11 個測試全部通過
  - Login: 成功、密碼錯誤、帳號不存在、Header 格式錯誤、缺少 userId
  - Refresh: 成功、無效 token
  - Logout: 登出後 refresh 失敗
  - JWT Filter: 無 token → 401、有效 token → 通過、無效 token → 401

## 7. 其他

- [x] 7.1 V3 migration — 修正 seed data 密碼 hash
- [x] 7.2 application.properties 拆分 (共用 + dev profile)
