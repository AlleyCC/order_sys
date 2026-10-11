# Proposal: add-login-rate-limit

## Why

`POST /login/create_token` 沒有任何限流,攻擊者可以無限次猜密碼。但限流不只是選一個演算法,還要決定限制的對象是誰:只依 IP 限流,攻擊者換 IP 就能繞過,同一個 NAT 後面的正常使用者也會被一起擋;只依帳號鎖定,攻擊者可以故意輸錯別人的密碼,把對方鎖在門外;固定時間窗在窗口交界處可以打進兩倍流量。單一個 key 擋不住所有攻擊形狀,所以要分層。

## What Changes

- 登入新增三層限流,在解密密碼與比對 BCrypt **之前**判斷,被擋的請求不消耗密碼驗證的運算:
  1. **來源 IP**:同一 IP 的登入嘗試數上限,不分帳號,擋單一來源的大量請求(NAT 後的多位使用者共用此額度,見 design Risks)。對付密碼噴灑(很多帳號 × 少數密碼)
  2. **帳號 + 來源 IP**:同一 IP 對同一帳號的嘗試數上限;登入成功即歸零。對付暴力破解(一個帳號 × 很多密碼),且攻擊者只能擋住自己,鎖不到帳號本人
  3. **帳號全域**:同一帳號來自「不受信任 IP」的嘗試數上限。對付分散式 IP 猜同一帳號。超過上限時,**曾經成功登入過該帳號的 IP 仍可登入**,不是把帳號整個鎖住
- 帳號一律取請求帶的 `userId` 字串,正規化(去頭尾空白、轉小寫)後作為 key,不論帳號是否存在都計數,避免藉由限流行為判斷帳號是否存在
- 被限流時回覆與帳密錯誤**完全相同**的 HTTP 401「帳密錯誤」,攻擊者分不出被擋還是猜錯
- 使用滑動時間窗計數,不使用固定時間窗
- 新增 Redisson 依賴(只用 `redisson` 本體,不用 spring-boot-starter),與現有 `StringRedisTemplate` 並存;現有 Redis 程式碼不動
- Redis 故障時限流 fail-open(放行並記 log),登入功能不因限流元件故障而中斷
- 來源 IP 取 TCP 連線位址(`getRemoteAddr()`),不採信 `X-Forwarded-For`

**不在範圍內**

- Passwordless(Passkey / WebAuthn、magic link)——根本解法,但等於重做登入流程
- CAPTCHA、MFA、登入異常通知
- 反向代理(nginx)與 `X-Forwarded-For` 的可信代理設定——目前沒有代理,日後部署再做,屆時只改設定
- 對使用者顯示「還要等多久」(回 401 的代價,見 design)
- `/auth/refresh` 與其他端點的限流

## Capabilities

### New Capabilities

- `login-rate-limit`: 登入嘗試的三層限流——來源 IP、帳號 + 來源 IP、帳號全域(受信任 IP 豁免),以及被擋時的回應、帳號 key 的正規化、Redis 故障時的行為

### Modified Capabilities

(無。登入成功與帳密錯誤的既有行為不變,記載於 `docs/SPEC.md`;本變更只新增「嘗試過多時也回同樣的 401」。)

## Impact

- **相依套件**:`pom.xml` 新增 `org.redisson:redisson`
- **程式碼**:新增 `RedissonConfig`(沿用 `spring.data.redis.*` 連線設定)、登入限流元件;`AuthService.login` 在解密前呼叫限流判斷,成功時通知限流元件;`AuthController` 取得來源 IP 傳入
- **設定**:新增 `app.login-limit.*` 門檻與時間窗設定
- **Redis**:新增限流 key 與「帳號 → 受信任 IP」紀錄,皆有 TTL
- **API**:`POST /login/create_token` 回應格式不變;嘗試過多時回 401
- **文件**:`docs/SPEC.md` 登入流程補上限流說明
- **不影響**:token 簽發與驗證、RBAC、結算佇列、通知推播
