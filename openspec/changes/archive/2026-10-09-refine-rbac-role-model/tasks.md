# Tasks: refine-rbac-role-model

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
組別已依相依性排序——第 1 組的 migration 是後續所有整合測試的前提。

## 1. V9 Migration:角色重整

- [x] 1.1 撰寫 `V9__refine_rbac_roles.sql`:新增 `ADMIN_STAFF`(行政)、`ACCOUNTANT`(會計)兩個角色,description 註明職能
- [x] 1.2 同一 migration 登記後台唯讀資源:`/admin/orders/**`(GET)、`/admin/transactions/**`(GET)
- [x] 1.2b 實作期修正:`/order/cancel_order`、`/order/delete_user_order` 不登記為資源(直接自 V9 移除,見 design.md D4)
- [x] 1.7 啟動時清除 `resources:all` 與各角色的資源快取(`RbacCacheInitializer`,見 design.md D9)
- [x] 1.3 同一 migration 建立 `role_resources`:分類三支 → `ADMIN_STAFF`;`/admin/orders/**` → `CUSTOMER_SERVICE`;`/admin/transactions/**` → `ACCOUNTANT`
- [x] 1.4 同一 migration 刪除 `LEADER`、`MEMBER`,並以註解寫明 FK CASCADE 會連帶清除既有使用者的 `user_roles`(預期行為,非資料遺失)
- [x] 1.5 同一 migration `ALTER TABLE users DROP COLUMN role`(add-rbac-permission 兩段式遷移的第二段)
- [x] 1.6 以 `./mvnw flyway:migrate` 驗證,並查詢確認:`roles` 剩四列、`role_resources` 對應正確、`user_roles` 僅 admin 一列、`users` 無 `role` 欄位

## 2. 既有測試的角色改名

- [x] 2.1 `RbacScenarioAcceptanceTest`:`LEADER` → `ADMIN_STAFF`,移除 `MEMBER` 的指派(改為零角色);情境 5「撤權即時生效」路徑維持不變
- [x] 2.2 `RbacAdminControllerTest`:角色清單斷言改為四個新角色;`updateUserRoles` 案例改用 `ADMIN_STAFF`
- [x] 2.3 `RbacMapperTest`、`DynamicAuthorizationManagerTest`、`RbacCacheServiceTest`:`LEADER`/`MEMBER` 一律改為 `ADMIN_STAFF`
- [x] 2.4 新增 `RbacScenarioAcceptanceTest` 案例:零角色使用者呼叫未受管制端點放行、呼叫受管制端點 403
- [x] 2.5 全數綠燈後執行 `./mvnw test` 確認無殘留引用

## 3. 訂單資料範圍:列表分頁 SQL

- [x] 3.1 (RED)`OrderMapperTest`(`@MybatisPlusTest` + Testcontainers):驗證分頁只回「我發起的 ∪ 我下單的」訂單
- [x] 3.2 (RED)同測試補案例:同一筆訂單既是我發起又有我下單 → 只出現一次;未參與任何訂單 → 空清單且總筆數 0
- [x] 3.3 (GREEN)改寫 `OrderMapper.getAllOrdersWithStore`:加入 `userId` 參數,以 `EXISTS` 子查詢半連接 `order_items`(避免 join 放大後再去重),確認分頁總筆數反映套用範圍後的結果
- [x] 3.4 `OrderService.getAllOrders` 改為接收呼叫者身分並傳入 mapper;`OrderController` 改以 `@AuthenticationPrincipal` 取得

## 4. 訂單資料範圍:明細與帳戶

- [x] 4.1 (RED)`OrderControllerTest`:非參與者查詢訂單明細 → 403 且回覆不含訂單任何欄位;參與者(發起者/下單者)→ 200
- [x] 4.2 (GREEN)`OrderService.getOrderDetail` 加入參與者檢查
- [x] 4.3 (RED)`OrderControllerTest`:`get_user_account` 帶他人 `userId` → 回覆仍為本人餘額
- [x] 4.4 (GREEN)`OrderController.getUserAccount` 移除 `userId` query param,改用 `@AuthenticationPrincipal`;`OrderService.getUserAccount(String)` 保留供內部呼叫(`deleteUserOrder` 的餘額通知仍需查他人)

## 5. 跨 ownership 的救援操作(OV-2)

- [x] 5.1 (RED)`OrderControllerTest`:客服角色取消他人發起的 OPEN 訂單 → 200 且狀態轉 CANCELLED、結算排程移除
- [x] 5.2 (RED)同測試:客服刪除他人的品項 → 200 並發出該品項擁有者的餘額更新通知
- [x] 5.3 (RED)同測試:既非開團者亦無客服/超管角色者取消他人訂單 → 403 且狀態未變更
- [x] 5.4 (RED)撤銷客服角色後,同一顆 token 的下一次取消他人訂單 → 403(放在 `OrderControllerTest`,因為需要訂單 fixture)
- [x] 5.5 (GREEN)`OrderService` 注入 `RbacCacheService`,`cancelOrder` 與 `deleteUserOrder` 三處 `"admin".equals(role)` 改為查詢角色是否含 `SUPER_ADMIN` 或 `CUSTOMER_SERVICE`(端點本身不登記,授權層維持登入即可)
- [x] 5.6 (REFACTOR)刪除 `OrderController.getCurrentRole()`,三支端點的 `role` 參數一併移除

## 6. 後台唯讀端點

- [x] 6.1 (RED)新增 `AdminQueryControllerTest`:客服呼叫 `/admin/orders/**` → 回傳不受參與範圍限制的訂單;無對應角色者 → 403
- [x] 6.2 (RED)同測試:會計呼叫 `/admin/transactions/**` 指定某使用者 → 回傳該使用者交易紀錄;無對應角色者 → 403
- [x] 6.3 (GREEN)新增 `AdminQueryController` 與對應 service,重用既有查詢邏輯,僅提供唯讀操作
- [x] 6.4 確認 `/admin/orders/**`、`/admin/transactions/**` 未被誤加入 `SecurityConstants.WHITE_LIST`

## 7. 文件同步

- [x] 7.1 `docs/SPEC.md` 2.7.1 角色清單改為四個新角色,刪除團長/團員的角色敘述並說明其由訂單資料判斷
- [x] 7.2 `docs/SPEC.md` 561、594、1160 三處 admin override 敘述改為「客服或超級管理員」
- [x] 7.3 `docs/SPEC.md` 訂單列表、訂單明細、帳戶餘額三支端點補上資料可見範圍說明;新增後台唯讀端點的 API 條目
- [x] 7.4 `docs/SPEC.md` 資料表章節移除 `users.role` 欄位

## 8. 驗收

- [x] 8.1 `./mvnw test` 全綠(單一執行 240 個測試,0 失敗 0 錯誤)
- [x] 8.2 `docker compose down -v && docker compose up -d` 後 `./mvnw flyway:migrate`,確認自 V1 全新建置可通過
- [x] 8.3 `openspec validate refine-rbac-role-model --strict`
- [x] 8.4 以 `admin` 實際呼叫一輪:指派 `ADMIN_STAFF` 給 bob → bob 可建分類 → 撤銷 → bob 立即 403(驗證同一顆 token 即時生效)
