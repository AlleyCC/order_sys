# Design: refine-rbac-role-model

## Context

動機見 proposal.md「Why」。以下是形塑本設計的現況約束:

- 授權層(`DynamicAuthorizationManager`)的五步判斷已穩定,本次不動其結構:未認證 → deny;`SUPER_ADMIN` → 放行;未命中 `resources` → 放行;命中則比對 `role_resources`
- 第 3 步「未登記即放行」代表**收緊一支端點的唯一手段是把它登記進 `resources`**。反過來說,任何端點只要沒登記,「登入即可」
- `RbacCacheService.readThrough` 已處理空清單快取(防穿透),零角色使用者不會每次請求打 DB
- `RbacAdminService.updateUserRoles` 已支援 `roleNames: []`(全量替換為空),零角色是既有的一等狀態,非邊界情況
- 業務層的 ownership 檢查(`orders.created_by`、`order_items.user_id`)與 RBAC 是兩套獨立機制。add-rbac-permission 的 design.md 將 ownership 列為 Non-Goal
- 訂單列表 SQL(`OrderMapper.getAllOrdersWithStore`)目前是無條件分頁

## Goals / Non-Goals

**Goals:**

- 讓 `roles` 表的每一列都有一致的語意與實際權限,消除空殼角色
- 讓「看得到誰的資料」成為可授權、可撤銷的權限,而非所有登入者的預設能力
- 修復 add-rbac-permission 遺留的 `getCurrentRole()` 死碼與隨之失效的爭議處理能力,使 `docs/SPEC.md` 與程式碼一致

**Non-Goals:**

- 不引入多租戶。`user_roles` 無 scope 欄位,角色為全系統唯一;跨租戶需另行設計(`user_roles` 加租戶欄位、快取 key 帶租戶)
- 不做退款(`REFUND`)與儲值。`ACCOUNTANT` 本次僅取得唯讀對帳權限
- 不改動授權層五步判斷的結構,不改快取策略
- 不新增「動態建立角色」的管理端 API。角色仍由 migration 定義

## Decisions

### D1:角色的判準是「能不能寫在員工名冊上」

`roles` 只收錄全系統有效、由管理員指派、跟人走的職能。由此導出六條規則:

1. RBAC 只放「全系統、由管理員指派、跟人走」的權限
2. 「跟單筆資料走」的關係一律不進 RBAC,由 service 層比對 `created_by` / `user_id`
3. 不建立沒有資源的角色——每個角色至少對應一組實際端點
4. 超級管理員維持程式碼不變量,不靠 `role_resources`,避免誤刪後無人能修
5. 未登記端點維持登入即可,要收緊就登記進 `resources`
6. `roles` 是員工名冊。訂單週期內的身分(團長/團員)由訂單資料判斷,一般使用者 `user_roles` 為空

**替代方案:** 保留 `LEADER`/`MEMBER` 作為「使用者分類」。否決,因為兩者都不是管理員能指派的——團長由開團動作產生、團員由下單產生,放進角色表後永遠不會有人去維護 `user_roles`,也永遠不會有 `role_resources`,就是現在這種空殼狀態。

### D2:`MEMBER` 與 `LEADER` 一併刪除,不保留為預設角色

規則 3 嚴格套用會同時殺掉 `MEMBER`(它同樣沒有任何 `role_resources`)。兩條路:為規則 3 加一條「預設角色」例外,或直接刪除。

**選擇刪除。** `LEADER`/`MEMBER` 是同一組概念的兩端(團長/團員),放行一個而刪另一個並不自洽。刪除後 `user_roles` 的語意收斂成「後台職能指派表」,與 D1 的判準完全對齊,規則 3 不需要任何例外條款。

**代價:** 「這個人存在」只剩 `users` 表能回答,`user_roles` 不再是全體使用者的完整名單。可接受——判斷使用者是否存在本來就該查 `users`。

### D3:`users.role` 舊欄位隨本次 migration 移除

`MEMBER` 刪除後,`users.role` 的 `'employee'` 值已無對應角色,`'admin'` 雖對得上 `SUPER_ADMIN` 但已無人讀取(`getCurrentRole()` 本次一併刪除)。留著只會製造「哪一個才算數」的疑問。add-rbac-permission 的兩段式遷移在此完成第二段。

**替代方案:** 留到另一個 change。否決,因為移除 `MEMBER` 之後舊欄位的語意當場就壞了,兩件事不宜分開。

### D4:客服取得爭議處理的寫入權(而非純唯讀)

客服若只能唯讀,爭議的收尾只能是「請開團者自己取消」,開團者不配合即無解。三個選項:

| 選項 | 客服能力 | 否決理由 |
|---|---|---|
| 純唯讀 | 只能查 | 角色無實質職能,退化成客訴接線生 |
| **唯讀 + 取消訂單/刪品項**(採用) | 直接處理爭議 | — |
| 唯讀 + 退款 | 退款收尾 | 需 `REFUND` 型別、退款端點、餘額回沖,範圍暴增,另開 change |

`cancel_order` 與 `delete_user_order` 端點已存在,本次不新增業務功能。

**實作期修正:** 最初的做法是把這兩支端點登記進 `resources` 並授權給 `CUSTOMER_SERVICE`。實作後測試證明這是錯的:授權層對一支端點是全有或全無,一旦登記,只有被授權的角色過得去;`MEMBER` 刪除後一般使用者沒有任何角色,連開團者都無法取消自己的團(`OrderControllerTest.alreadySettled` 由 400 變成 403)。這也違反 D5 自己劃下的界線——「能不能取消**這一筆**訂單」是 service 層的問題,不是端點層級的問題。錯誤的登記已自 V9 移除(該 migration 未進 main,因此直接改寫,而不是補一支抵銷的 migration:若部署中斷在兩者之間,零角色使用者連自己的團都取消不了)。

改為:**兩支端點不登記(維持登入即可),客服的跨 ownership 能力純由 service 層查角色決定。** 撤權即時生效不受影響,因為 service 每次請求都查 `RbacCacheService`。

代價是粒度變粗:管理員只能整個拔掉某人的客服角色,無法在後台單獨關閉「客服可取消訂單」。要細到那個程度,得另做一支 `POST /admin/orders/cancel_order` 專用端點再登記授權;目前沒有這個需求,等有再做。

### D5:service 層的跨 ownership 檢查改查 RBAC,不重新引入 authorities

`OrderService` 三處 `"admin".equals(role)` 的來源 `OrderController.getCurrentRole()` 恆回 `"employee"`(authorities 在 token 移除 role claim 後永遠為空),管理員例外實際上已失效。兩條路:

- **OV-1** 刪除 override,取消/刪品項一律 owner-only,並同步刪除 `docs/SPEC.md` 的相關敘述
- **OV-2**(採用)刪除 `getCurrentRole()`,三處改注入 `RbacCacheService` 查角色

選 OV-2,因為 D4 已決定客服要能處理爭議,而且現狀連超級管理員都無法取消他人的團,這是缺陷而非設計。

**不採用「把角色塞回 authorities」**:那等於把 Day 11 移除 role claim 的決策倒退回去——authorities 若在認證階段填入,就成了請求生命週期內的快照,撤權無法即時生效。改為在需要時查 `RbacCacheService`,維持「每次請求現查」的一致語意。

**設計張力(需明確記載):** add-rbac-permission 的 design.md 將 ownership 列為 RBAC 的 Non-Goal(「RBAC 只管能不能呼叫這類 API」)。本決策讓 service 層開始詢問 RBAC。界線定為:**跨 ownership 的救援操作依系統角色判斷;一般操作的 ownership 仍由 service 層自行比對。** 授權層仍只回答「能不能呼叫這支 API」,service 層才回答「能不能動這一筆資料」。

### D6:收緊既有查詢端點,而非只加後台端點

`get_all_orders` 回全系統訂單、`get_user_account?userId=` 可查任何人餘額,兩者皆為「登入即可」。若只新增 `/admin/orders/**` 而不收緊舊端點,客服看得到的東西一般會員也看得到,新角色沒有任何權限差別——直接違反規則 3。

| 選項 | 否決理由 |
|---|---|
| 舊端點登記為資源並同時授權給客服與一般使用者 | 需為「一般使用者」建一個涵蓋全體的角色,與 D2 衝突 |
| 舊端點不動,只加後台端點 | 客服角色無實質差別 |
| **舊端點收緊 + 新增後台唯讀端點**(採用) | — |

後台端點獨立於一般端點,而非在同一支端點內依角色切換回傳範圍:同一支端點回傳範圍隨角色浮動,會讓前端與測試都難以推理,也讓「這支端點回什麼」不再是端點契約的一部分。

### D7:「我參與的訂單」= 發起 ∪ 下單,於 SQL 層套用

範圍定義為 `orders.created_by = :me` 聯集 `order_items.user_id = :me` 的訂單。

**於 SQL 層套用而非查出後過濾**:分頁的總筆數必須反映套用範圍後的結果,先分頁再過濾會得到每頁筆數不一的破碎結果。實作上 `getAllOrdersWithStore` 需加入對 `order_items` 的半連接並去重(`EXISTS` 子查詢優於 `JOIN + DISTINCT`,避免 join 放大後再去重)。這支 SQL 需要獨立的 `@MybatisPlusTest` 驗證分頁與去重。

### D8:`ACCOUNTANT` 現在就建立,唯讀對帳端點即其資源

會計的完整職能(儲值、調帳)需要餘額寫入功能,而系統目前**沒有任何增加餘額的路徑**——`UserMapper` 只有 `casDebit` 一支,`TradeType.RECHARGE` 僅存在於資料模型與 V1 seed,無 API 可產生。

但 D6 要做的 `/admin/transactions/**`(查任一使用者交易紀錄)正是對帳所需,足以讓 `ACCOUNTANT` 立即符合規則 3。儲值端點完成後再追加授權即可,不需為了建角色而先做儲值。

### D9:啟動時清除 migration 管轄的 RBAC 快取

`resources` 與 `role_resources` 只由 migration 變動,沒有任何寫入端會刪對應的 Redis key(`evictAllResources()` 在本次之前零呼叫點),失效完全靠 24 小時 TTL。部署通常不會清 Redis,新版啟動後快取仍是舊的授權規則:

- `rbac:resources:all` 舊 → 本次新登記的 `/admin/orders/**`、`/admin/transactions/**` 被判定為「未登記」→ 授權層第 3 步放行 → **fail-open**,全系統訂單與任一使用者的交易紀錄對所有登入者開放,最久 24 小時
- `rbac:role-resources:{role}` 舊 → 新授權的資源讀不到 → **fail-closed**,該用的人被擋

前者是安全問題,後者是功能問題,同源,因此在 `ApplicationReadyEvent`(Flyway 已在 context 初始化期間跑完)一併清除。

**不清 `rbac:user-roles:{userId}`:** per-user key 數量不設限,沒有便宜的列舉方式。migration 改動使用者角色(如本次刪除 `MEMBER` 指派)只會讓快取殘留一個已不存在的角色名,該角色查不到任何資源,不影響授權結果,留給 TTL。

## Risks / Trade-offs

- **前端相容性** → `get_all_orders` 的回傳範圍與 `get_user_account?userId=` 的行為皆改變。屬 BREAKING,需在 `docs/SPEC.md` 標明;此專案無正式前端,影響限於 API 契約文件與測試
- **刪除角色連帶清除 `user_roles`** → `roles` 的 FK 為 `ON DELETE CASCADE`,刪除 `LEADER`/`MEMBER` 會一併清掉 alice/bob/charlie 的 `user_roles` 列。這是預期行為(他們本就該是零角色),但 migration 必須明確註解,避免日後被誤讀為資料遺失
- **service 層依賴 RBAC** → 見 D5 的設計張力。緩解:界線寫入 spec(「跨 ownership 的救援操作依系統角色判斷」),並限縮於 `cancelOrder` / `deleteUserOrder` 兩處,不擴散
- **分頁 scope SQL 的正確性** → 去重與總筆數是最容易出錯的地方。緩解:mapper 整合測試涵蓋「同時發起並下單」與「未參與任何訂單」兩個案例
- **測試面積** → `LEADER`/`MEMBER` 在五個測試檔共數十處引用。緩解:一律改為 `ADMIN_STAFF`,`RbacScenarioAcceptanceTest` 情境 5 的「撤權即時生效」路徑不變,只換角色名
- **後台端點的授權依賴登記** → `/admin/orders/**`、`/admin/transactions/**` 若忘記寫進 `resources`,依第 3 步會變成「登入即可」,等於把全系統資料對所有人開放。緩解:acceptance test 必須包含「無角色者呼叫後台端點回 403」
- **快取讓「已登記」延遲生效** → 同上,但成因是 Redis 舊資料而非漏寫 migration。見 D9;啟動清快取後,殘留風險僅剩「啟動後、清快取前」的極短視窗

## Migration Plan

單一 V9 migration,順序如下(皆在同一個 migration 內,Flyway 以單一 transaction 執行):

1. `INSERT roles`:`ADMIN_STAFF`、`ACCOUNTANT`
2. `INSERT resources`:`/admin/orders/**`(GET)、`/admin/transactions/**`(GET)
3. `INSERT role_resources`:分類三支 → `ADMIN_STAFF`;`/admin/orders/**` → `CUSTOMER_SERVICE`;`/admin/transactions/**` → `ACCOUNTANT`
4. `DELETE FROM roles WHERE name IN ('LEADER','MEMBER')`——FK CASCADE 連帶清除 `user_roles` 與 `role_resources`(兩者本就為空)
5. `ALTER TABLE users DROP COLUMN role`

**部署順序:** 步驟 2 的四筆資源一經登記即刻生效,未取得授權者立即 403。因此 migration 與程式碼必須同版部署——若 migration 先行而 `AdminQueryController` 尚未存在,新端點回 404(無害);若程式碼先行而 migration 未跑,新端點因未登記而「登入即可」(**有害**,全系統訂單對所有人開放)。**程式碼不得先於 migration 部署。**

**回滾:** 反向 migration 需重建 `users.role` 欄位並自 `user_roles` 回填(`SUPER_ADMIN` → `'admin'`,其餘 → `'employee'`),成本高。實務上以「修正後向前滾」為主要策略。

## Open Questions

- `/admin/orders/**` 與 `/admin/transactions/**` 的查詢參數與分頁形式(依使用者篩選、依時間區間篩選)可於實作時依既有查詢端點的慣例決定,不影響本設計的授權結構
- 後台唯讀端點未來若要擴充(如 `/admin/users/**`),是否沿用「一個職能一組路徑前綴」的切法,留待實際需求出現時再定
