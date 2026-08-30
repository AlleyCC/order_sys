# Proposal: add-rbac-permission

## Why

後台將有多種角色(超級管理員、團長、客服),且角色與權限會持續調整。目前的授權機制把角色寫死在兩個地方——JWT claim(登入時烙進 token)與 `@PreAuthorize("hasRole('ADMIN')")`(編譯進程式碼)——任何權限調整都需要重新部署或重新登入才生效,無法支撐「管理員在後台即時調整角色權限」的營運需求。同時單一 `users.role` 欄位無法表達一帳號多角色。

## What Changes

- 新增 RBAC 資料模型:`roles`、`resources`(後端 API URL pattern + HTTP method)、`user_roles`、`role_resources` 四張表;一帳號可擁有多角色,一角色可對應多個資源
- 授權判斷改為動態:自訂 `AuthorizationManager<RequestAuthorizationContext>` 在請求時比對「該 URL 需要的權限」與「該使用者實際擁有的權限」,規則來自 DB,調整後即時生效(不重啟、不重新登入)
- **BREAKING**:JWT access token 移除 `role` claim,token 退化為純身份憑證(只含 `sub` + `jti`);角色與權限一律於每次請求時查詢(Redis 快取 → DB)
- **BREAKING**:移除 controller 上寫死的 `@PreAuthorize("hasRole('ADMIN')")`,改由動態授權統一處理
- 未登入(401)與已登入但無權限(403)回覆不同的錯誤語意:補上 `AccessDeniedHandler`,與既有 `AuthenticationEntryPoint` 對稱
- 白名單機制:登入、token refresh、Swagger/API docs 等端點免登入(集中設定,可調整)
- 權限查詢走 Redis Cache-Aside:`user-roles:{userId}` 與 `role-resources:{roleName}` 兩組 key;管理端寫入 DB 後刪除對應 key(不更新),下次請求 miss 重建
- Redis 故障時降級:授權查詢 fallback 直接讀 DB,授權功能不中斷
- 新增管理端 API:調整角色↔資源對應、調整使用者↔角色對應(調整後即時生效)
- Migration:建四張新表 + 將既有 `users.role` 資料搬移至 `user_roles`(兩段式遷移,舊欄位暫留)

## Capabilities

### New Capabilities

- `rbac-authorization`: 每次請求的身份驗證與動態授權——JWT 身份識別、URL 動態權限比對、401/403 語意區分、白名單、權限快取與 Redis 故障降級
- `rbac-management`: 角色與資源的管理端操作——調整角色的資源權限、調整使用者的角色,寫入後即時生效(快取失效)

### Modified Capabilities

(無——目前無既有 main specs;登入/refresh 行為的變動屬 token 內容的實作細節,對外行為不變)

## Impact

- **DB**:新增 V6 migration(4 張新表 + 種子資料 + `users.role` 資料搬移)
- **程式碼**:
  - `SecurityConfig`:改用自訂 AuthorizationManager,補 `accessDeniedHandler`
  - `JwtAuthenticationFilter`:改為只解析身份(userId),authorities 改由授權層查詢
  - `JwtUtils.generateAccessToken`:移除 role 參數
  - `AuthService` 登入流程:不再把 role 塞入 token
  - `CategoryController`:移除 `@PreAuthorize`(授權改由動態層接手)
  - 新增:RBAC entities/mappers、權限快取 service(含降級)、動態 AuthorizationManager、管理端 controller/service
- **Redis**:新增兩組快取 key;沿用既有 Redis 基礎設施(`TokenRedisService` 模式)
- **相容性**:前端需處理 403 新錯誤格式;既有 401 行為不變。`users.role` 欄位保留至下一個 change 才移除
- **範圍外**:「團長只能管理自己發起的團購活動」為資料層 ownership 檢查,由各業務 service 層處理,不在本次 RBAC 範圍
