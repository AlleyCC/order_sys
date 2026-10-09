# Proposal: settlement-notification-inbox

## Why

結算結果目前只透過 Redis Pub/Sub → WebSocket 推播。Pub/Sub 不保存訊息,使用者剛好離線或正在重連時,通知就永遠消失,之後也沒有任何地方查得到。

三種結算結果的影響不同:

- **結算成功**:扣款已和 `transactions` 寫在同一個交易,使用者查交易紀錄看得到,但沒有任何東西提醒他去看
- **餘額不足(FAILED)**:沒有留下任何給使用者的紀錄。需要儲值的人不知道要儲值,其他參與者的金額持續凍結,也不知道原因。這是最需要使用者採取行動的情況
- **自動重試用完(abandoned)**:團主與管理員收不到就沒人手動結算,訂單會一直卡在 `OPEN`/`CLOSED`

另外,扣款遇到第一個餘額不足的人就停止,所以系統只知道「其中一人不夠」,沒辦法通知所有需要儲值的人。

## What Changes

- 新增站內通知(`notifications` 表):結算結果寫進 DB,保存每位收件人的未讀/已讀狀態
- **通知和它描述的狀態變化寫在同一個交易**:
  - 結算成功:通知和扣款、`SETTLED`、交易紀錄在同一個交易
  - 餘額不足:「訂單改為 `FAILED`」和通知包成同一個交易(目前 `FAILED` 是在扣款交易回滾後單獨寫入的)
  - 自動重試用完:沒有對應的狀態變化;同一張訂單、同一位收件人只會存一則,重送不會重複
- 扣款改為**先試扣全部參與者,再判斷成敗**:結算仍維持全有全無,任何一人餘額不足,整個扣款交易就回滾,但會找出**所有**餘額不足的人
- 結算成功時,團主(不論是否參與)收到「結算成功,共收 X 元,可以向店家訂購」。團主是看到結算成功才去訂購的;團主同時是參與者時,只收這一則,內容另外附上自己被扣的金額與餘額
- 餘額不足時,參與者依身分收到不同內容:
  - 餘額不足者:「你的餘額不足,請儲值」
  - 餘額足夠者:「有參與者餘額不足,訂單暫未成立,你的金額仍凍結」
  - 團主(不論是否參與):「結算失敗,餘額不足的人是:…」,列出所有餘額不足者。團主才是要去催大家儲值的人;團主同時是參與者時,只收這一則
- 每次結算失敗都發一則新通知,不合併(團主重試後再失敗,參與者會再收到一則)
- **行為改變**:手動 `POST /order/pay_order` 目前不發任何通知,改為和自動結算一樣寫入通知並推播
- 推播保留,定位改為「盡力送達」;推播內容附上 `notificationId`,讓前端可以和查詢結果去重
- 新增 API:查詢自己的未讀通知、將單則通知標為已讀
- `pay_order` 餘額不足時,錯誤訊息改為列出所有餘額不足的使用者

**不在範圍內**

- 部分結算(只扣餘額足夠的人)。維持全有全無,理由是團主看到結算成功才去訂購
- 一鍵全部已讀、查詢已讀的歷史通知、刪除通知
- 推播失敗後的重試(收件人是人,改由「上線時查一次未讀」補齊,不需要像 ruoyi `PayNotifyTask` 那樣依頻率重試)
- 下單成功的餘額推播(`BalanceChangeNotifier`),維持現狀
- 前端重連時的查詢實作

## Capabilities

### New Capabilities

- `settlement-notification`:結算結果通知的寫入時機與交易邊界、各結算結果的收件人與內容、去重規則、未讀查詢與標為已讀

### Modified Capabilities

(無。`openspec/specs/` 目前只有 RBAC 相關 spec,結算行為尚未有主 spec)

## Impact

- **DB**:新增 Flyway migration `V10`,建立 `notifications` 表
- **程式**:
  - `PaymentService.executePayment`:迴圈改為收集所有餘額不足者,成功時在同一個交易寫通知
  - `OrderService.payOrder`:餘額不足時改呼叫新的交易方法(條件式寫入 `FAILED` 並寫通知);commit 之後推播
  - `OrderService.settleOrder`:推播移到 `payOrder`,讓手動結算也會推播
  - `OrderService.notifySettlementAbandoned`:先寫通知(去重),再推播
  - `InsufficientBalanceException`:改為攜帶所有餘額不足的 userId
  - 新增 `Notification` entity、`NotificationMapper`、通知查詢 controller
- **API**:新增 2 支(登入即可,不需要 RBAC 登記);`pay_order` 餘額不足的錯誤訊息格式改變
- **文件**:`docs/SPEC.md` 的 API、DB schema、結算流程章節
