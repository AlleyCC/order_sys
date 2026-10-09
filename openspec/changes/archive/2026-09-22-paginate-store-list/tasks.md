# Tasks: paginate-store-list

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
第 1 組先建立 page/size 的共用契約,後續各端點都依賴它。

## 1. 分頁參數契約(`paged-query`)

- [x] 1.1 新增 `PageLimits` 常數類別(`MAX_SIZE = 20`),見 design.md D1
- [x] 1.2 (RED)`OrderControllerTest` 的 `/order/get_all_orders`:`page=0`、`size=0`、`size=21` → 400,且 `detail` 指出不合法的參數;`size=20` → 200
- [x] 1.3 (GREEN)`GlobalExceptionHandler` 新增 `HandlerMethodValidationException` 處理:取第一個違反的 message 當 `detail`,回 400(見 design.md D2)
- [x] 1.4 (GREEN)`OrderController.getAllOrders` 的 page/size 加上 `@Min` / `@Max` 註解;controller 類別**不**加 `@Validated`
- [x] 1.5 (RED → GREEN)`AdminQueryController` 的 `/admin/orders/get_all_orders`:同 1.2 的案例,再補上註解

## 2. 分類改用註解(`paged-query`)

- [x] 2.1 (RED)`CategoryControllerTest`:`size 超過上限 100` 案例改為 `size=21` → 400;新增 `size=20` → 200 的邊界案例;`page=0` 案例補上 `detail` 斷言
- [x] 2.2 (GREEN)`CategoryController.getCategories` 的 page/size 加上註解
- [x] 2.3 (REFACTOR)刪除 `CategoryService` 內的 page/size `if` 與 `MAX_PAGE_SIZE`;保留 `categoryId`/`categoryName` 互斥檢查(見 design.md D6)
- [x] 2.4 刪除 `CategoryServiceTest` 的 `pageBelowOneRejected`、`sizeOverLimitRejected`,確認其餘案例仍綠燈

## 3. 店家列表需登入(`store-listing`)

- [x] 3.1 (RED)`RbacScenarioAcceptanceTest` 白名單情境:未登入存取 `/order/get_all_shops` 改為預期 401
- [x] 3.2 (RED)`OrderControllerTest`:零角色的一般使用者帶 token 查詢店家列表 → 200
- [x] 3.3 (GREEN)自 `SecurityConstants.WHITE_LIST` 移除 `/order/get_all_shops`;`OrderController.getAllShops` 移除 `@SecurityRequirements`,並更新 `@Operation` 說明

## 4. 店家列表分頁、欄位與排序(`store-listing`)

- [x] 4.1 (RED)`OrderControllerTest`:回覆為分頁物件(`records`、`page`、`size`、`total`、`totalPages`);`records[0]` 恰好只有 `storeId`、`storeName`、`minOrderAmount`
- [x] 4.2 (RED)依低消由低到高:V1 seed 的三家店順序為 250 → 300 → 350
- [x] 4.3 (RED)同低消跨頁不重複也不遺漏:插入一批前綴 `分頁測試店-`、同低消的店家,逐頁查完,斷言所有頁的 `storeId` 集合**恰好等於**插入的集合(見 design.md D7)
- [x] 4.4 (RED)頁碼超過總頁數 → 200、`records` 為空、`total` 不變
- [x] 4.5 (RED)`/order/get_all_shops` 的 `size=21` → 400(page/size 契約套用到店家列表)
- [x] 4.6 (GREEN)新增 `dto/response/StoreResponse` record 與 `from(Store)`(見 design.md D3)
- [x] 4.7 (GREEN)`OrderService.getAllShops(storeName, page, size)` 改為 `selectPage`,排序 `min_order_amount ASC, store_id ASC`(見 design.md D4)
- [x] 4.8 (GREEN)`OrderController.getAllShops` 接收 `page`/`size`(含註解),回傳 `PageResponse<StoreResponse>`

## 5. 店家名稱模糊查詢(`store-listing`)

- [x] 5.1 (RED)`storeName=八方` 只回「八方雲集(烏日店)」
- [x] 5.2 (RED)`storeName=`(空字串)與只含空白的值 → 與未帶參數相同
- [x] 5.3 (RED)`storeName=%`、`storeName=_` → `records` 為空、`total` 為 0
- [x] 5.4 (RED)無符合結果 → 200、`total` 為 0、`totalPages` 為 0
- [x] 5.5 (GREEN)`OrderService.getAllShops` 加上名稱條件:`trim`、空白視為未帶、跳脫 `\` → `%` → `_`(順序見 design.md D5)
- [x] 5.6 (GREEN)`OrderController.getAllShops` 新增選填參數 `storeName`

## 6. 既有測試、文件與收尾

- [x] 6.1 `ApiAccessLogAspectTest.logsPublicEndpoint` 驗的是「匿名呼叫記成 anonymous」,改用仍在白名單內、且在切面範圍(`controller` 套件)內的端點:`POST /login/logout`(空 body)。不要補 token——補了就不是在測匿名。實作期修正:原訂的 `/v3/api-docs` 屬於 springdoc,不在切面的 `within(controller..*)` 範圍內,不會產生 log
- [x] 6.2 更新 `docs/SPEC.md` 3.3 `GET /order/get_all_shops`:需登入、Request 參數(`page`、`size`、`storeName`)、分頁 Response 範例與排序規則
- [x] 6.3 更新 `docs/SPEC.md` 中分類查詢的 size 上限(100 → 20),以及兩支訂單列表的 page/size 範圍
- [x] 6.4 執行 `./mvnw test` 全數綠燈
