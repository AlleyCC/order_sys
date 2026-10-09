-- PayPool Schema V10: 下單的 Idempotency-Key 記錄
-- client 重送同一次下單時(帶同一個 Idempotency-Key),回傳第一次寫入的品項,不重複寫入。
-- 記錄與品項在同一個交易中寫入:下單失敗時一併回滾,不留記錄。
-- 保存 24 小時,由 IdempotencyKeyCleanupJob 定期刪除。

CREATE TABLE `order_item_idempotency_keys` (
  `id`              BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`         VARCHAR(20) NOT NULL,
  -- 預設 collation 不分大小寫,"abc" 與 "ABC" 會被當成同一個 key,所以這欄改用 ascii_bin
  `idempotency_key` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 不設 FK:品項之後可能被刪除,重送仍應回傳當初的 item_id
  `item_id`         INT         NULL COMMENT '第一次寫入的品項;與品項在同一交易中寫入',
  `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_user_key` (`user_id`, `idempotency_key`),
  KEY `idx_idempotency_created_at` (`created_at`),
  CONSTRAINT `fk_idempotency_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
