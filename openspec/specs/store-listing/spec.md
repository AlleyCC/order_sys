# store-listing Specification

## Purpose

定義店家列表查詢的行為:登入使用者開團前用來瀏覽與查找可選的店家,支援分頁與依名稱部分比對過濾;回覆只帶選店所需的摘要欄位,排序穩定以確保翻頁時資料不重複、不遺漏。

## Requirements

### Requirement: 店家列表需登入

店家列表查詢 SHALL 僅允許已登入的使用者呼叫;未登入的請求 MUST 收到 HTTP 401。任何已登入帳號 MUST 皆可查詢,不需具備特定角色。

#### Scenario: 未登入查詢店家列表被拒

- **WHEN** 未帶有效憑證的請求查詢店家列表
- **THEN** 回覆 401

#### Scenario: 沒有任何角色的使用者可查詢

- **WHEN** 一位未被指派任何角色的一般使用者查詢店家列表
- **THEN** 回覆 200 與店家資料

### Requirement: 店家列表以分頁物件回覆

店家列表 SHALL 以分頁物件回覆,包含:當頁資料 `records`、目前頁碼 `page`、每頁筆數 `size`、符合條件的總筆數 `total`、總頁數 `totalPages`。`total` 與 `totalPages` MUST 依套用名稱過濾後的結果計算。

#### Scenario: 回覆包含分頁資訊

- **WHEN** 使用者以 `page=1&size=2` 查詢,系統中有 3 家店
- **THEN** `records` 含 2 筆,`page` 為 1,`size` 為 2,`total` 為 3,`totalPages` 為 2

#### Scenario: 超過最後一頁回空資料

- **WHEN** 使用者查詢的頁碼大於總頁數
- **THEN** 回覆 200,`records` 為空陣列,`total` 仍為符合條件的總筆數

### Requirement: 店家資料只回摘要欄位

每筆店家 SHALL 只包含店家識別 `storeId`、名稱 `storeName` 與最低訂購金額 `minOrderAmount`。回覆 MUST NOT 包含電話、地址、建立與更新時間,或任何未明列於此的欄位。

#### Scenario: 不回傳未列出的欄位

- **WHEN** 使用者查詢店家列表
- **THEN** 每筆店家恰好只有 `storeId`、`storeName`、`minOrderAmount` 三個欄位

### Requirement: 店家列表依低消穩定排序

店家列表 SHALL 依最低訂購金額由低到高排序;最低訂購金額相同者 MUST 再依店家識別由小到大排序,使整體順序完全確定。以相同條件逐頁查詢時,每家店 MUST 恰好出現在一頁中——不重複,也不遺漏。

#### Scenario: 依低消由低到高

- **WHEN** 系統中有低消 350、250、300 的三家店,使用者查詢第一頁
- **THEN** 回覆順序為 250、300、350

#### Scenario: 同低消時跨頁不重複也不遺漏

- **WHEN** 系統中有多家低消相同的店家,使用者以小於總筆數的 size 逐頁查完所有頁
- **THEN** 所有頁的店家識別合起來,恰好等於全部符合條件的店家,沒有重複也沒有缺漏

### Requirement: 依店家名稱部分比對過濾

店家列表 SHALL 接受選填的名稱參數 `storeName`;帶入時只回名稱中包含該字串的店家。參數前後空白 MUST 忽略;空白或空字串 MUST 視同未帶入,回全部店家。

輸入中的 `%` 與 `_` MUST 視為一般字元比對,不得作為萬用字元——否則輸入 `%` 會比對到所有店家。

#### Scenario: 部分比對

- **WHEN** 使用者以 `storeName=八方` 查詢,系統中有「八方雲集(烏日店)」與「茶湯會(烏日中山店)」
- **THEN** 只回「八方雲集(烏日店)」

#### Scenario: 空白參數視同未帶

- **WHEN** 使用者以 `storeName=`(空字串)或只含空白的值查詢
- **THEN** 回覆與未帶 `storeName` 相同

#### Scenario: 百分號不作萬用字元

- **WHEN** 使用者以 `storeName=%` 查詢,系統中沒有任何名稱含 `%` 的店家
- **THEN** 回覆 `records` 為空,`total` 為 0

#### Scenario: 底線不作萬用字元

- **WHEN** 使用者以 `storeName=_` 查詢,系統中沒有任何名稱含 `_` 的店家
- **THEN** 回覆 `records` 為空,`total` 為 0

#### Scenario: 無符合結果

- **WHEN** 使用者以一個沒有任何店家名稱包含的字串查詢
- **THEN** 回覆 200,`records` 為空,`total` 為 0,`totalPages` 為 0
