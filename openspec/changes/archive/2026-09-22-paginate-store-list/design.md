# Design: paginate-store-list

## Context

動機見 proposal.md - Why;行為要求見 `specs/store-listing`、`specs/paged-query`。

與設計相關的現況:

- 四支分頁端點的 page/size 都是 `@RequestParam(defaultValue = ...) int`,只有 `CategoryService.getCategories` 自己用 `if` 檢查(`MAX_PAGE_SIZE = 100`,丟 `BadRequestException`)
- 專案已引入 `spring-boot-starter-validation`,Spring Boot 3.5(Spring Framework 6.2),目前沒有任何 controller 標註類別層級 `@Validated`
- `GlobalExceptionHandler` 處理了 `MethodArgumentNotValidException`(body 驗證)與 `MethodArgumentTypeMismatchException`(`?page=abc`),但**沒有**處理 `HandlerMethodValidationException`
- 分頁回應已有 `PageResponse.from(IPage, mapper)`;分類用 `CategoryResponse::from` 的 record + 靜態工廠作為 DTO 慣例
- 未登記於 `resources` 表的路徑,`DynamicAuthorizationManager` 對任何已登入者放行

## Goals / Non-Goals

**Goals:**

- page/size 規則在 controller 簽章上一眼可見,且四支端點行為一致
- 店家列表的排序在任何資料分布下都完全確定

**Non-Goals:**

- 兩支訂單端點的**回應外形**不動(目前直接回 MyBatis-Plus 的 `IPage`,未走 `PageResponse`),本次只補參數檢查
- 不把店家查詢從 `OrderService` 拆出成 `StoreService`,端點路徑也維持 `/order/get_all_shops`
- 不為 `store_name` 建索引:`LIKE '%x%'` 開頭即萬用字元,B-tree 索引本來就用不上,店家數量級也不需要
- 不做游標分頁(cursor-based)

## Decisions

### D1:page/size 用參數註解宣告,上限集中成常數

```java
@RequestParam(defaultValue = "1")  @Min(value = 1, message = "page 必須大於等於 1") int page,
@RequestParam(defaultValue = "10") @Min(value = 1, message = "size 必須介於 1 到 20")
                                   @Max(value = PageLimits.MAX_SIZE, message = "size 必須介於 1 到 20") int size
```

上限值放在一個常數類別(例如 `PageLimits.MAX_SIZE = 20`),四支端點引用同一個常數;某支端點日後需要不同上限時,直接在該端點寫自己的值即可,不影響其他端點。

- **為什麼不用共用 helper(`PageRequests.of(page, size, max)`)**:使用者定調 page/size 屬於 API 契約,契約應該出現在 controller 簽章與 Swagger 文件上,而不是藏在 service 內
- **為什麼不包成 `PageQuery` 物件(`@Valid PageQuery q`)**:註解屬性必須是編譯期常數,物件上的 `@Max` 會把所有端點綁在同一個上限,與「各端點可有不同上限」的目標衝突

### D2:依賴 Spring 內建 method validation,不加 `@Validated`

Spring 6.1+ 在 controller 參數帶有約束註解時會自動驗證,違反時丟 `HandlerMethodValidationException`。在 `GlobalExceptionHandler` 新增處理:取第一個違反的 message 當 `detail`,回 400 `ProblemDetail`,與既有 400 格式一致。

- **為什麼不在 controller 類別加 `@Validated`**:加了會改走 AOP 的 `MethodValidationPostProcessor`,丟的是 `ConstraintViolationException`,目前沒有 handler → 回 500。同一個註解會因為有沒有 `@Validated` 走完全不同的路徑,因此要寫一個測試(例如 `size=21` → 400 且 `detail` 含 `size`)把這條路徑釘住,日後有人順手加上 `@Validated` 時會立刻紅燈

### D3:`StoreResponse` 沿用 record + 靜態工廠

```java
public record StoreResponse(String storeId, String storeName, Integer minOrderAmount) {
    public static StoreResponse from(Store s) { ... }
}
```

與 `CategoryResponse` 同一種慣例;controller 以 `PageResponse.from(result, StoreResponse::from)` 組回應。

### D4:排序鍵 `min_order_amount ASC, store_id ASC`

`min_order_amount` 預設值為 0,同值很常見;`store_id` 是主鍵,補上後排序完全確定。分頁 `OFFSET` 只有在排序完全確定時才保證跨頁不重複、不遺漏。

### D5:名稱比對的萬用字元跳脫

MyBatis-Plus 的 `.like(col, v)` 只會在前後各補一個 `%`,不會跳脫 `v` 內的 `%` 與 `_`。查詢前先處理輸入:

1. `trim()`,空字串視為 null(`like` 的 condition 參數為 false 時不加入條件)
2. 依序把 `\` → `\\`、`%` → `\%`、`_` → `\_`(**`\` 必須最先處理**,否則會把剛補上的跳脫字元再跳脫一次)

MySQL 的 `LIKE` 預設跳脫字元就是 `\`;值以 prepared statement 參數傳入,不經過 SQL 字串字面值解析,所以只需要一層跳脫。跳脫邏輯先寫成 `OrderService` 內的 private 方法,等第二個模糊查詢出現時再抽出。

- **替代方案**:用 `.apply("store_name LIKE CONCAT('%', {0}, '%') ESCAPE '!'")` 明確指定跳脫字元。優點是不受 `NO_BACKSLASH_ESCAPES` sql_mode 影響;缺點是欄位名稱寫死成字串,失去 lambda 的型別檢查。目前 sql_mode 未啟用該選項,選擇前者,並以 Testcontainers 的真實 MySQL 測試驗證

### D6:分類改用註解後移除 service 內的檢查

`CategoryService.getCategories` 的 page/size `if` 與 `MAX_PAGE_SIZE` 常數刪除;「`categoryId` 與 `categoryName` 不可同時使用」屬於業務規則,保留在 service。`CategoryServiceTest` 中兩個 page/size 案例隨之刪除,改由 `CategoryControllerTest` 涵蓋(上限案例改為 `size=21`,並新增 `size=20` 可通過的邊界案例)。

### D7:測試資料與既有資料共存

整合測試共用同一個 DB,不能假設 `stores` 只有 V1 seed 的 3 筆。「同低消跨頁不重複、不遺漏」的測試做法:

1. 插入一批名稱帶唯一前綴(例如 `分頁測試店-`)且 `min_order_amount` 相同的店家
2. 以 `storeName=分頁測試店` 搭配小於總筆數的 `size` 逐頁查詢
3. 斷言所有頁的 `storeId` 合起來**恰好等於**插入的集合——同時驗證沒有重複、也沒有遺漏(只驗「不重複」的話,漏掉資料的錯誤測不出來)

這個測試同時覆蓋名稱過濾、`total` 的計算與排序的穩定性。

## Risks / Trade-offs

- [對外破壞性變更:店家列表需登入、外形與欄位改變、分類 size 上限降為 20] → 目前沒有已知的外部前端呼叫方;同步更新 `docs/SPEC.md` 3.3,並在 commit 訊息中標明 BREAKING
- [移除 `CategoryService` 的檢查後,service 層不再自我防衛] → 目前唯一的呼叫者是 controller;日後若有其他呼叫者(例如排程),需在該處自行保證參數合法
- [`\` 跳脫依賴 MySQL 未啟用 `NO_BACKSLASH_ESCAPES`] → 以 Testcontainers 真實 MySQL 測試 `%`、`_` 案例;若日後啟用該 sql_mode,測試會紅燈,屆時改用 D5 的替代方案
- [有人在 controller 加上 `@Validated`,越界從 400 變 500] → D2 的測試釘住

## Migration Plan

無 DB migration。部署即生效;回滾只需還原程式碼。
