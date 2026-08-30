# Tasks: add-rbac-permission

## 1. DB Migration 與資料模型

- [x] 1.1 撰寫 V6 migration:建立 `roles`、`resources`、`user_roles`、`role_resources` 四張表(UNIQUE 複合鍵、FK),依 design.md D7 欄位定義
- [x] 1.2 V6 種子資料:四個角色(SUPER_ADMIN/LEADER/CUSTOMER_SERVICE/MEMBER)、既有受管制端點登記進 resources(不掛 role_resources:超管靠程式碼不變量,行為與現狀等價)、依 `users.role` 現值搬遷 user_roles(admin→SUPER_ADMIN,employee→MEMBER)
- [x] 1.3 依 CLAUDE.md 流程驗證 migration(`flyway:migrate` + SQL 抽查:表結構、種子筆數、搬遷結果)
- [x] 1.4 建立 entities(Role/Resource/UserRole/RoleResource)與 mappers;RED:先寫 `@MybatisPlusTest` mapper 測試(查使用者角色名清單、查角色資源清單的 join query),GREEN:實作 XML/annotation query

## 2. 權限快取服務(含降級)

- [x] 2.1 RED:`RbacCacheService` 單元測試——命中快取不查 DB、miss 時查 DB 並回寫、Redis 讀取拋例外時 fallback DB 且不拋出、刪 key 失敗時吞例外
- [x] 2.2 GREEN:實作 `RbacCacheService`(`rbac:user-roles:{userId}`、`rbac:role-resources:{roleName}`,Cache-Aside + TTL 兜底 + try/catch 降級,依 design.md D3/D4)

## 3. 動態授權

- [x] 3.1 RED:`DynamicAuthorizationManager` 單元測試——未認證 deny、SUPER_ADMIN 放行、URL pattern 命中且有權限放行、命中但無權限 deny、未登記端點放行、多角色取聯集、method 比對(含 ALL)
- [x] 3.2 GREEN:實作 `DynamicAuthorizationManager`(PathPatternParser 比對,依 design.md D2 判斷順序)
- [x] 3.3 改造 `JwtAuthenticationFilter`:不再讀 `role` claim,authorities 留空(或僅標記已認證),身份 = userId
- [x] 3.4 改造 `SecurityConfig`:白名單抽成 `SecurityConstants.WHITE_LIST`、`anyRequest().access(dynamicAuthorizationManager)`、補 `accessDeniedHandler`(403 JSON)、移除 `@EnableMethodSecurity`
- [x] 3.5 `JwtUtils.generateAccessToken` 移除 role 參數,`AuthService` 登入流程同步修改;移除 `CategoryController` 的 `@PreAuthorize`
- [x] 3.6 全量跑既有測試,修正因 token/授權改動而紅掉的測試(另修環境問題:缺 import、缺 ./key/ 金鑰)

## 4. 管理端 API

- [x] 4.1 RED:`RbacAdminController` 整合測試(`@SpringBootTest`+MockMvc+Testcontainers)——查角色清單/資源清單/某角色資源、調整角色資源、調整使用者角色;非 SUPER_ADMIN 呼叫回 403 且無資料異動
- [x] 4.2 GREEN:實作管理端 service + controller(寫 DB 後刪對應 Redis key)

## 5. 行為情境驗收(整合測試,對應 spec 六情境)

- [x] 5.1 情境 1+4:登入成功取得憑證;無憑證/過期憑證呼叫受保護 API 回 401「未登入」;白名單端點未登入可存取
- [x] 5.2 情境 2+3:有權限操作成功;無權限操作回 403「權限不足」且無資料異動;401 與 403 JSON 可區分
- [x] 5.3 情境 5:調整角色權限後不重啟、不重新登入,同一顆 token 下一個請求即套用(加權限變可用、移除權限變 403、拔使用者角色即失效)
- [x] 5.4 情境 6:模擬 Redis 不可用(停用獨立 container),已登入有權限的操作仍成功;另發現並修復 filter 黑名單檢查未降級的缺口(fail-open)
- [x] 5.5 全量 `./mvnw test` 綠燈(202/202);啟動 app 以 seed 帳號煙霧測試 8/8 通過(admin 全通、alice 受限、401/403 語意正確、白名單開放)

## 6. 收尾

- [x] 6.1 更新 `docs/SPEC.md`:新增 2.7 RBAC 動態授權(模型、判斷順序、401/403 格式、快取/降級、管理端 API、資料表),更新 2.5 受保護路徑表
- [x] 6.2 盤點殘留的 `users.role` 讀取方(main code 已無;順帶修復 WebSocketConfig 讀已移除 role claim 的 NPE 炸彈);更新 memory 追蹤項

## 7. Token 認證邏輯去重 + WS 攔截器測試(design.md D9)

- [x] 7.1 RED:`TokenAuthenticator` 單元測試——有效 token 回 Authentication(principal=userId、無 authorities)、過期/無效回 empty、已拉黑回 empty、黑名單檢查 Redis 炸掉 fail-open 回 Authentication
- [x] 7.2 RED:`WsAuthChannelInterceptor` 單元測試——CONNECT 無 header 拒連、認證失敗拒連、成功 setUser、非 CONNECT frame 原樣放行
- [x] 7.3 GREEN:實作 `TokenAuthenticator`(fail-open 邏輯自 filter 搬入)與 `WsAuthChannelInterceptor`(具名類)
- [x] 7.4 重構兩個入口:`JwtAuthenticationFilter` 與 `WebSocketConfig` 改用共用元件;全量測試綠燈(212/212)
