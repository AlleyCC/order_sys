-- PayPool Schema V8: 移除 refresh_tokens
-- Refresh token 自 phase8-redis-token(2026-04-11)起改存 Redis(key: refresh:{tokenId}),
-- 此表從那之後不再有任何讀寫。當時的 design.md 刻意保留 schema 作為回退路徑;
-- Redis 方案已沿用至今,不再需要回退,連同 RefreshToken entity / mapper 一併移除。
-- 此表只有指向 users 的外鍵,沒有其他表參照它,可直接 DROP。

DROP TABLE IF EXISTS `refresh_tokens`;
