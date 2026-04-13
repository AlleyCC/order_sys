## Why

目前系統沒有統一的 API 存取日誌機制，排查問題時需要逐個 controller 翻 log。加入 AOP 統一記錄 request/response，讓每個 API 呼叫都有一致的日誌格式（method、path、參數、status、耗時），提升可觀測性，也是後端工程師面試常被問到的基礎建設。

## What Changes

- 新增 `@Aspect` 類別，用 `@Around` advice 攔截所有 Controller 方法
- 統一記錄：HTTP method、URI、request params/body、response status、執行時間 (ms)
- 敏感欄位（如 password）需脫敏處理
- 異常情況也要記錄（包含 exception class 和 message）

## Capabilities

### New Capabilities
- `api-access-logging`: 使用 Spring AOP 對所有 Controller 方法進行統一的 request/response 日誌記錄

### Modified Capabilities

（無既有 spec 需修改）

## Impact

- 新增依賴：`spring-boot-starter-aop`
- 新增類別：`ApiAccessLogAspect`（在 config 或新建 aspect package）
- 不影響現有 API 行為，純粹是橫切關注點
- 日誌量會增加，但 INFO level 且內容可控
