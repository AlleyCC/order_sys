# Proposal: refine-rbac-role-model

## Why

`roles` 表目前混進了兩種性質不同的東西:`SUPER_ADMIN`/`CUSTOMER_SERVICE` 是「跟人走、由管理員指派」的系統職能,`LEADER`/`MEMBER` 卻是「跟單筆訂單走」的訂單內身分——團長是開團這個動作本身產生的(記在 `orders.created_by`),團員是下單產生的(記在 `order_items.user_id`),兩者都不是管理員能指派的。混在一起的後果是 `LEADER`、`CUSTOMER_SERVICE`、`MEMBER` 三個角色至今沒有任何 `role_resources`、沒有任何實質權限差別,整套 RBAC 實際上只有超級管理員一種權限。

同時,add-rbac-permission 把 role claim 從 token 移除後,`OrderController.getCurrentRole()` 讀取的 authorities 恆為空,`OrderService` 三處 `"admin".equals(role)` 的管理員例外全部失效——`docs/SPEC.md` 仍載明「admin 可刪除任何訂單中的任何品項」,但實際上連超級管理員都無法取消他人的團。爭議處理能力目前在系統中不存在。

## What Changes

**角色模型**

- **BREAKING**:刪除 `LEADER` 與 `MEMBER` 兩個角色。團長/團員是訂單週期內的身分,由 `orders.created_by` 與 `order_items.user_id` 判斷,不進 `roles` 表。一般使用者的 `user_roles` 為空(零角色),此狀態已為管理端 API 一等支援(`roleNames: []` = 拔掉所有角色)
- 新增 `ADMIN_STAFF`(行政,維護商品主檔)與 `ACCOUNTANT`(會計,對帳)兩個角色
- 既有的分類三支端點授權給 `ADMIN_STAFF`;`CUSTOMER_SERVICE` 與 `ACCOUNTANT` 各自取得下述新端點的授權——四個角色皆有實際對應資源,不留空殼
- **BREAKING**:移除 `users.role` 舊欄位(add-rbac-permission 兩段式遷移的第二段)。`MEMBER` 刪除後 `'employee'` 已無對應角色,留著只會與 `user_roles` 互相矛盾

**資料可見範圍**

- **BREAKING**:`GET /order/get_all_orders` 由「回全系統訂單」收緊為「只回我開的團 ∪ 我有下單的團」
- **BREAKING**:`GET /order/get_order_detail` 加上參與者檢查,非參與者回 403
- **BREAKING**:`GET /order/get_user_account` 忽略 `userId` query param,一律以憑證身分查詢(目前任何登入者可查任何人餘額)
- 新增後台唯讀端點 `GET /admin/orders/**`(全系統訂單,授權給 `CUSTOMER_SERVICE`)與 `GET /admin/transactions/**`(任一使用者交易紀錄,授權給 `ACCOUNTANT`)

**跨 ownership 的救援操作**

- 移除死碼 `OrderController.getCurrentRole()`,`OrderService` 三處 `"admin".equals(role)` 改為查詢 RBAC 角色
- 啟動時清除 `resources` / `role_resources` 的快取:兩者只由 migration 變動,原本只靠 24h TTL,部署後會沿用舊的授權規則
- `POST /order/cancel_order` 與 `POST /order/delete_user_order` 維持「登入即可」(不登記進 `resources`),由 service 層判斷:開團者,或具 `CUSTOMER_SERVICE` / `SUPER_ADMIN` 角色者。客服因此能處理爭議(取消他人的團、刪除他人的品項),而開團者取消自己的團不受影響

## Capabilities

### New Capabilities

- `order-data-scope`: 訂單、帳戶與交易紀錄查詢的資料可見範圍——一般使用者只看得到自己參與的資料,後台角色透過獨立的唯讀端點查看全系統資料

### Modified Capabilities

- `rbac-authorization`: 新增「`roles` 僅收錄系統職能角色」的要求(訂單週期內的身分不得進入角色表);新增「跨 ownership 的救援操作依系統角色判斷」的要求;既有 scenario 中以「團長」為例的措辭改為「行政」
- `rbac-management`: 既有 scenario 中以「團長」為例的措辭改為「行政」;新增「使用者可被拔除所有角色後仍能使用未受管制端點」的 scenario

## Impact

- **DB**:V9 migration——刪除 `LEADER`/`MEMBER`(FK CASCADE 連帶清除 `user_roles`)、新增 `ADMIN_STAFF`/`ACCOUNTANT`、登記後台唯讀資源、建立 `role_resources` 對應、`DROP COLUMN users.role`
- **程式碼**:
  - `OrderController`:移除 `getCurrentRole()`,三支端點不再傳 `role` 參數
  - `OrderService`:`getAllOrders` 加 scope(需重寫 `getAllOrdersWithStore` 的分頁 SQL,join `order_items` 去重)、`getOrderDetail` 加參與者檢查、`getUserAccount` 改用憑證身分、`cancelOrder`/`deleteUserOrder` 的管理員例外改查 `RbacCacheService`
  - 新增 `AdminQueryController` + service:`/admin/orders/**`、`/admin/transactions/**`
- **測試**:`RbacScenarioAcceptanceTest`、`RbacAdminControllerTest`、`RbacMapperTest`、`DynamicAuthorizationManagerTest`、`RbacCacheServiceTest` 中的 `LEADER`/`MEMBER` 改為 `ADMIN_STAFF`;`OrderController`/`OrderService` 相關測試需補資料範圍案例;新增 mapper 測試驗證分頁 scope SQL
- **文件**:`docs/SPEC.md` 2.7 角色清單、561/594/1160 三處 admin override 敘述、訂單查詢端點的資料範圍說明
- **相容性**:前端若依賴 `get_user_account?userId=` 查他人餘額或依賴 `get_all_orders` 取得全系統訂單,行為將改變
- **範圍外**:退款(`REFUND`)與儲值功能。`ACCOUNTANT` 本次僅取得唯讀對帳權限,儲值端點完成後再授權
