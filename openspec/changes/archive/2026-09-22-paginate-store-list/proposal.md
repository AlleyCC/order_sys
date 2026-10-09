# Proposal: paginate-store-list

## Why

`GET /order/get_all_shops` 目前是免登入的白名單端點,以 `selectList(null)` 一次回傳全部店家,而且直接序列化 `Store` entity——`phone`、`address`、`createdAt`、`updatedAt` 都會送出去,與 `docs/SPEC.md` 3.3 載明的三個欄位不符;日後 `stores` 表新增任何內部欄位也會自動外洩。店家數量成長後,前端需要分頁與依名稱查找。

同時,專案既有三支分頁端點的 page/size 規則散落各處:`/category/get_categories` 在 service 內以 `if` 檢查(上限 100),`/order/get_all_orders` 與 `/admin/orders/get_all_orders` 則完全沒有檢查,`?size=100000` 可一次撈出全部資料。規則沒有集中宣告,第四支分頁端點加入前應先統一。

## What Changes

**店家列表**

- **BREAKING**:`GET /order/get_all_shops` 移出免登入白名單,未登入回 401。不登記於 `resources` 表,任何登入帳號皆可查詢
- **BREAKING**:回傳由陣列 `[...]` 改為分頁物件 `{ records, page, size, total, totalPages }`(沿用既有 `PageResponse`)
- **BREAKING**:每筆店家只回 `storeId`、`storeName`、`minOrderAmount`(新增 `StoreResponse`,不再直接回傳 entity)
- 新增選填參數 `storeName`,以名稱部分比對過濾;輸入中的 `%`、`_` 視為一般字元,不作萬用字元
- 排序固定為低消由低到高,同低消再依店家 ID 排序,確保跨頁不重複、不遺漏

**分頁參數契約**

- page/size 的合法範圍改由 controller 參數註解宣告,屬於 API 契約的一部分;四支分頁端點一致:`page >= 1`、`1 <= size <= 20`,預設 `page=1`、`size=10`
- 超出範圍回 400,錯誤格式與專案其他 400 一致(`{ status, detail }`)
- `/order/get_all_orders`、`/admin/orders/get_all_orders` 新增 page/size 檢查
- **BREAKING**:`/category/get_categories` 的 size 上限由 100 降為 20;原本寫在 `CategoryService` 的 page/size 檢查移除,改由 controller 註解負責

## Capabilities

### New Capabilities

- `store-listing`: 店家列表查詢——登入後可分頁瀏覽店家、依名稱部分比對過濾;回覆只含摘要欄位;排序穩定可翻頁
- `paged-query`: 分頁查詢端點共同的參數契約——page/size 的預設值、合法範圍與越界時的錯誤回覆

### Modified Capabilities

(無。`rbac-authorization` 的「白名單端點免登入」要求未列舉店家列表,將其移出白名單只是白名單內容的變動,不改變該要求本身。)

## Impact

- **程式碼**:`OrderController`、`OrderService.getAllShops()`、`AdminQueryController`、`CategoryController`、`CategoryService`、`SecurityConstants.WHITE_LIST`、`GlobalExceptionHandler`(新增 method validation 例外處理);新增 `dto/response/StoreResponse`
- **API**:`/order/get_all_shops` 需登入、回傳外形與欄位改變;`/category/get_categories` size 上限降為 20
- **測試**:`RbacScenarioAcceptanceTest` 的白名單斷言(未登入存取店家列表)需反轉為 401;`OrderControllerTest` 的店家列表斷言需改讀 `$.records`;`ApiAccessLogAspectTest` 的匿名 log 案例需改用其他白名單端點;`CategoryControllerTest` 的「size 超過 100」改為「超過 20」;`CategoryServiceTest` 的 page/size 案例隨檢查移除
- **文件**:`docs/SPEC.md` 3.3 店家列表的 Request/Response 範例
- **不影響**:資料庫 schema(無 migration)、RBAC 資源登記
