# Tasks: add-login-rate-limit

依 TDD 進行:每組先寫失敗的測試(RED),再實作(GREEN),最後整理(REFACTOR)。
限流行為以 `@SpringBootTest` + `MockMvc` + Testcontainers 驗證;以 `request.setRemoteAddr(...)` 模擬不同來源 IP。

## 1. 基礎

- [x] 1.1 `pom.xml` 加入 `org.redisson:redisson`(鎖定版本,確認與 Spring Boot 3.5 / Java 25 相容)
- [x] 1.2 新增 `RedissonConfig`:讀取 `spring.data.redis.host` / `port` / `password` 建立 `RedissonClient`(design.md D1);確認既有測試全數通過,`StringRedisTemplate` 未被取代
- [x] 1.3 新增 `LoginLimitProperties`(`app.login-limit.*`:三層的 rate、interval,受信任 IP 保留天數),預設值依 design.md D2;`application-test.properties` 調高門檻(design.md D10)
- [x] 1.4 `LoginRequest.userId` 加上 `@Size(max = 20)`

## 2. 驗證 Redisson 行為(spike,寫成測試留著)

- [x] 2.1 (RED→GREEN)`RRateLimiter` rate=3 / interval=2 秒:連續第 4 次被拒;等滿 interval 後可再取得——確認是滑動窗口而非固定窗口(design.md D2)
- [x] 2.2 確認 limiter 的 key 設定過期後 Redis 中確實帶 TTL
- [x] 2.3 `RSetCache.add(ip, ttl)` 後 `contains(ip)` 為真;成員過期後為假

## 3. L1 來源 IP(`login-rate-limit`:依來源 IP 限制登入嘗試)

- [x] 3.1 (RED)`LoginRateLimitTest`:同一 IP 第 6 次登入請求回 401,即使帳密正確
- [x] 3.2 (RED)IP A 達上限後,IP B 以正確帳密登入成功
- [x] 3.3 (RED)每次帶不同 `X-Forwarded-For`,同一連線來源仍被拒
- [x] 3.4 (GREEN)新增 `LoginRateLimiter`,實作 L1;`AuthController` 以 `getRemoteAddr()` 取得 IP 傳入 `AuthService.login`,限流判斷放在 RSA 解密之前(design.md D4、D8)

## 4. L2 帳號 + IP(`login-rate-limit`:依帳號與來源 IP 限制登入嘗試)

第 4、5 組寫在 `LoginAccountRateLimitTest`,以 `@TestPropertySource` 把 L1 調高,避免 L1 先擋(design.md D10)。

- [x] 4.1 (RED)同一 IP 對 alice 失敗 5 次後,第 6 次以正確密碼仍回 401
- [x] 4.2 (RED)攻擊者 IP 使 alice 達上限後,alice 從另一 IP 登入成功
- [x] 4.3 (RED)失敗 4 次 → 成功 → 再失敗 4 次 → 第 5 次仍進行密碼驗證(以正確密碼登入成功驗證)
- [x] 4.4 (RED)不存在的帳號 nobody99 第 6 次被拒;`alice` / `Alice` / `ALICE` 輪流累計第 6 次被拒
- [x] 4.5 (RED)userId 超過 20 字元回 400
- [x] 4.6 (GREEN)實作 L2 與帳號正規化;登入成功時刪除該組 limiter(design.md D3、D6)

## 5. L3 帳號全域與受信任 IP(`login-rate-limit`:依帳號全域限制登入嘗試,受信任 IP 豁免)

- [x] 5.1 (RED)20 個不受信任 IP 各嘗試 alice 1 次後,第 21 個不受信任 IP 以正確密碼仍回 401
- [x] 5.2 (RED)alice 先從 IP T 登入成功;L3 達上限後,從 IP T 以正確密碼登入成功
- [x] 5.3 (RED)L3 達上限後,從從未成功過的 IP 以正確密碼回 401
- [x] 5.4 (RED)受信任 IP 的失敗嘗試不消耗 L3 額度
- [x] 5.5 (RED)L3 達上限後 alice 從受信任 IP 登入成功,不受信任 IP 仍被拒(成功不歸零 L3)
- [x] 5.6 (GREEN)實作受信任 IP 紀錄(`RSetCache`,30 天)與 L3(design.md D4、D5)

## 6. 回應一致與故障處理(`login-rate-limit`:被限流的回應與帳密錯誤無法區分、限流元件故障時不中斷登入)

- [x] 6.1 (RED)分別取得帳密錯誤與被限流的回應,比對狀態碼、body 相同,且被限流回應沒有 `Retry-After`
- [x] 6.2 (RED)被限流時不呼叫密碼驗證(Mockito 驗證 `PasswordUtils` 未被呼叫,或以單元測試驗證)
- [x] 6.3 (RED)以 Mockito 讓 `RedissonClient` 拋出連線例外,正確帳密仍登入成功
- [x] 6.4 (GREEN)實作 fail-open 與 log(design.md D9)

## 7. 收尾

- [x] 7.1 REFACTOR:整理 `LoginRateLimiter` 與 `AuthService` 的介面(只留「可以嘗試嗎」「登入成功」兩個動作)
- [x] 7.2 跑完整測試套件,確認既有測試全數通過
- [x] 7.3 更新 `docs/SPEC.md`:登入流程(2.1)加入限流步驟、`POST /login/create_token` 補 400(userId 過長)與限流說明
