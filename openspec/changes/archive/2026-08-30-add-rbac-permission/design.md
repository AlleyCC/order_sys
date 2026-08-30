# Design: add-rbac-permission

## Context

動機見 proposal.md。與設計直接相關的現況:

- Spring Boot 3.5 / Spring Security 6:SS5 時代的 `FilterInvocationSecurityMetadataSource` + `AccessDecisionManager` + `Voter` 三件套已移除,參考實作 mall-tiny 的動態授權不能照抄,須改用 `AuthorizationManager<RequestAuthorizationContext>`
- 既有 `JwtAuthenticationFilter` 每次請求解析 JWT 並設定 SecurityContext;token 目前攜帶單一 `role` claim
- 既有 `TokenRedisService` 已建立 Redis 使用模式(jti 黑名單);Redis 基礎設施可直接沿用
- 既有 `SecurityConfig` 有 `authenticationEntryPoint`(401)但無 `accessDeniedHandler`(403 是 Spring 預設空回應)
- `users.role` 現值:`admin`、`employee`
- 錯誤回覆格式沿用 `GlobalExceptionHandler` 的既有 JSON 慣例

## Goals / Non-Goals

**Goals:**

- 授權規則完全 DB 驅動,管理端調整後下一個請求即生效
- 授權路徑的資料庫壓力與角色數量同階(而非與使用者數量同階)
- Redis 故障時授權功能降級可用,不中斷

**Non-Goals:**

- 資料層 ownership 檢查(「團長只能管自己的團」)——由各業務 service 層以 `leader_id` 比對處理,RBAC 只管「能不能呼叫這類 API」
- 角色/資源本身的 CRUD 管理介面(前端);本次只做後端 API
- 移除 `users.role` 舊欄位(留待下一個 change,兩段式遷移)
- 權限的階層/繼承(role hierarchy)——三個角色扁平即可

## Decisions

### D1. JWT 為純身份憑證,授權狀態每次請求查詢

Token 只含 `sub`(userId)+ `jti`,移除 `role` claim。「你是誰」由簽章保證,「你能做什麼」每次請求時自 Redis/DB 查詢。

- 理由:身份與授權分離後,任何權限調整(拔角色、改角色權限)都在下一個請求生效,無 token 存活期的延遲窗口;jti 黑名單退回只負責登出
- 替代方案:roles 放 claim(少一次查詢,但拔角色最長延遲 15 分鐘,且形成雙真相來源);claim + 版本號混合(省一次 hash 查詢,多一套版本管理,量級不值得)

### D2. 動態授權用自訂 `AuthorizationManager<RequestAuthorizationContext>`

`SecurityConfig` 改為 `anyRequest().access(dynamicAuthorizationManager)`。`check()` 內邏輯:

```
1. 白名單 → 放行(實際上白名單在 SecurityConfig permitAll 層先擋掉,見 D6)
2. 未認證 → deny(觸發 401 entry point)
3. 使用者角色含 SUPER_ADMIN → 放行(系統不變量,見 D5)
4. 以 request URI + method 比對 resources 表的 pattern(PathPatternParser)
   - 無命中 → 放行(登入即可,維持既有 anyRequest().authenticated() 行為)
   - 命中 → 使用者任一角色的資源清單含該 resource → 放行,否則 deny(403)
5. 步驟 4 所需的「user→roles」「role→resources」經快取服務取得(見 D3)
```

- 理由:SS6 唯一正道;單一函數介面,邏輯集中易測
- 替代方案:保留 `@PreAuthorize("hasAuthority('xxx')")`(端點↔權限碼綁在程式碼,只有角色↔權限碼可動態調;彈性較小,被否決——需求方向已確認要 URL 級動態對照)

### D3. 快取:Cache-Aside、以角色為主要粒度、寫時刪除

Redis key 設計:

| key | 內容 | 失效時機 |
|---|---|---|
| `rbac:user-roles:{userId}` | 該使用者的角色名清單 | 管理端調整該使用者角色時刪除 |
| `rbac:role-resources:{roleName}` | 該角色可用的資源清單(pattern+method) | 管理端調整該角色權限時刪除(情境 5) |
| `rbac:resources:all` | 全部已登記資源(判斷「端點是否受管制」用) | TTL 到期(本次 scope 內 resources 表僅由 migration 變動);另留 evict 方法供未來資源 CRUD 使用 |

註:第三組 key 是實作時發現的必要項——「未登記端點放行」的判斷需要全表,不能由各角色的授權清單推導(資源可能登記了但未掛任何角色,如 category 種子資源,此時僅超管可用,不等於未登記)。

- 寫入策略:更新 DB 後**刪除** key(不更新)。刪除失敗最壞是一次額外 miss,更新則可能因寫入競態留下永久髒資料
- 兩組 key 都設 TTL(如 24h)作為兜底,防止漏刪造成永久不一致
- 角色粒度的 key 全站只有個位數,情境 5 的失效成本 = 刪 1 個 key;user-roles 粒度失效只影響被調整的那一個使用者
- 替代方案:以使用者為粒度快取完整權限(上萬 key,角色權限調整時無法精準失效,被否決);本地 in-process cache + pub/sub 失效(多實例一致性複雜,量級不需要)

### D4. Redis 故障降級:cache service 內 try/catch fallback DB

新增 `RbacCacheService`:讀取時 Redis 拋例外(連線失敗等)→ log warn → 直接查 DB 回傳;寫入(刪 key)失敗 → log warn → 吞掉(TTL 兜底)。授權主流程對 Redis 故障無感。

- 理由:與 mall-tiny `RedisCacheAspect`+`@CacheException` 的效果等價,但顯式 try/catch 比 AOP 切面直白——本專案只有一個快取服務類,抽象成切面不值得
- 風險承擔:Redis 全掛期間所有授權查詢直打 DB;日活上萬的量級 MySQL 撐得住,屬可接受的降級狀態(見 Risks)

### D5. 超級管理員全通為程式碼不變量

`SUPER_ADMIN` 在 AuthorizationManager 內直接放行,不依賴 role_resources 逐筆授權。

- 理由:「超管什麼都能做」是系統公理而非可調整的權限資料;若放 DB,每新增一個 API 都要記得補一筆,漏補即產生「超管做不了某事」的矛盾狀態
- 替代方案:DB 全掛(資料驅動更一致,但引入上述維護負擔,被否決)

### D6. 白名單留在 `SecurityConfig` permitAll,集中為常數

白名單(登入、refresh、swagger、ws、公開查詢)抽成 `SecurityConstants.WHITE_LIST` 字串陣列,同時供 `permitAll()` 與其他需要判斷處引用。不放 DB——白名單變動本質上伴隨程式碼變動(新公開端點),沒有免部署調整的需求。

### D7. 資料模型與遷移

四張新表(BIGINT AUTO_INCREMENT PK、UNIQUE 複合鍵防重複授權):

```
roles(role_id, name UQ, description, status, created_at, updated_at)
resources(resource_id, url_pattern, http_method, name, category, created_at, updated_at)
user_roles(id, user_id FK, role_id FK, UQ(user_id, role_id), created_at)
role_resources(id, role_id FK, resource_id FK, UQ(role_id, resource_id), created_at)
```

- `roles.name` 存 enum 字串(`SUPER_ADMIN` / `LEADER` / `CUSTOMER_SERVICE` / `MEMBER`),符合專案「enum 存 VARCHAR」慣例,亦作為 Redis key 成分
- `resources.http_method`:`GET/POST/PUT/PATCH/DELETE/ALL`
- V6 migration:建表 + 種子角色與資源 + 依 `users.role` 現值搬遷(`admin`→`SUPER_ADMIN`,`employee`→`MEMBER`);`users.role` 欄位保留不動
- 種子資源:現有受管制端點(category 管理等)登記進 resources 並掛給對應角色,確保遷移後行為與現狀等價(admin 能做的事不變)

### D8. 401/403 回覆

補 `AccessDeniedHandler`,與既有 `AuthenticationEntryPoint` 對稱,JSON 格式對齊 `GlobalExceptionHandler` 慣例:

- 401:`{"status":401,"detail":"未登入或憑證已失效"}`
- 403:`{"status":403,"detail":"權限不足"}`

`@EnableMethodSecurity` 與 `CategoryController` 的 `@PreAuthorize` 移除,授權入口單一化(避免兩套授權疊加造成「動態表已開權限但註解仍擋」的矛盾)。

### D9. 共用 TokenAuthenticator + 具名 WS 攔截器(實作後追加)

「token → 已認證身份」邏輯原本重複於 HTTP filter 與 WS 匿名攔截器,且 WS 側黑名單檢查無降級、匿名類無法單元測試。重構:

- `TokenAuthenticator`:`Optional<Authentication> tryAuthenticate(String token)`——解析、黑名單檢查(fail-open)、組 Authentication;任何失敗回 empty(呼叫端不需要失敗原因,YAGNI)
- 「header 有沒有帶 token / Bearer 前綴」屬入口層職責,不進 authenticator
- WS 攔截器抽成具名類 `WsAuthChannelInterceptor`,可單元測試(選項 A;不做 WebSocketStompClient 端到端測試——flaky 成本高於「接線壞掉」的風險)
- 紅利:WS 黑名單檢查自動繼承 fail-open 降級

## Risks / Trade-offs

- [URL pattern 打錯字 = 沉默的授權漏洞或誤擋] → pattern 比對邏輯以單元測試完整覆蓋;整合測試逐一驗證種子資源與六個行為情境;資源表變更視同權限變更需 review
- [未登記端點預設「登入即可」,新增 API 忘記登記 = 任何登入者可呼叫] → 與現狀行為一致,非退步;種子資料把既有管制端點全數登記;後續可考慮反轉為 deny-by-default(記入 Open Questions)
- [Redis 全掛期間授權查詢直打 DB,尖峰時 DB 壓力上升] → user-roles/role-resources 查詢皆走索引且極輕;可接受;若未來量級成長,可加 in-process 短 TTL 二級快取
- [每次請求 2 次 Redis 讀取(user-roles + role-resources)] → hash/get 為 sub-ms 操作,可接受;不做預先合併避免失效複雜化
- [migration 搬遷後雙寫期:`users.role` 舊欄位仍存在但不再被授權使用] → 舊欄位僅殘留讀取方(如有)須在本 change 內一併切換;下一個 change 移除欄位
- [測試環境 Testcontainers 需同時起 MySQL + Redis] → 專案既有整合測試已具備此模式,沿用

## Migration Plan

1. V6 migration 上線(只加表加資料,不動舊欄位)——可獨立先行,無行為變化
2. 程式碼切換(filter、AuthorizationManager、快取服務、管理端 API)一次部署
3. 回滾:還原程式碼即可(舊 code 只讀 `users.role`,該欄位未動);V6 的表留著無害

## Open Questions

- 未登記端點是否改為 deny-by-default?(等資源表涵蓋率穩定後再評估;改動只在 AuthorizationManager 一處)
- `MEMBER` 角色的資源邊界(一般跟團操作是否納管)——本次先維持「登入即可」,待訂單類 API 需要差異化管制時再登記
