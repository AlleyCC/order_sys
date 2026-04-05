-- PayPool Schema V2: balance, refresh_tokens, status/type VARCHAR, FK
-- Depends on V1__init_schema.sql

-- ----------------------------
-- 1. users: add balance column
-- ----------------------------
ALTER TABLE `users` ADD COLUMN `balance` BIGINT NOT NULL DEFAULT 0 AFTER `role`;

-- Backfill balance from latest transaction's closing_balance per user
UPDATE `users` u
  JOIN (
    SELECT t1.user_id, t1.closing_balance
    FROM `transactions` t1
    INNER JOIN (
      SELECT user_id, MAX(created_at) AS max_created
      FROM `transactions`
      GROUP BY user_id
    ) t2 ON t1.user_id = t2.user_id AND t1.created_at = t2.max_created
  ) latest ON u.user_id = latest.user_id
SET u.balance = latest.closing_balance;

-- ----------------------------
-- 2. orders.status: TINYINT → VARCHAR
-- ----------------------------
ALTER TABLE `orders` MODIFY COLUMN `status` VARCHAR(20) NOT NULL DEFAULT 'OPEN';

UPDATE `orders` SET `status` = CASE `status`
  WHEN '0'  THEN 'OPEN'
  WHEN '1'  THEN 'CLOSED'
  WHEN '2'  THEN 'SETTLED'
  WHEN '-1' THEN 'CANCELLED'
  WHEN '-2' THEN 'FAILED'
  ELSE `status`
END;

-- ----------------------------
-- 3. transactions.type: TINYINT → VARCHAR
-- ----------------------------
ALTER TABLE `transactions` MODIFY COLUMN `type` VARCHAR(20) NOT NULL;

UPDATE `transactions` SET `type` = CASE `type`
  WHEN '1' THEN 'DEBIT'
  WHEN '2' THEN 'RECHARGE'
  ELSE `type`
END;

-- ----------------------------
-- 4. Drop token_blacklist, create refresh_tokens
-- ----------------------------
DROP TABLE `token_blacklist`;

CREATE TABLE `refresh_tokens` (
  `token_id`    VARCHAR(36)  NOT NULL COMMENT 'UUID',
  `user_id`     VARCHAR(20)  NOT NULL,
  `revoked`     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '0=active, 1=revoked',
  `expire_time` DATETIME     NOT NULL COMMENT 'Token 過期時間 (7 days)',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`token_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_expire_time` (`expire_time`),
  CONSTRAINT `fk_refresh_tokens_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 5. transactions.order_id: add FK
-- ----------------------------
ALTER TABLE `transactions`
  ADD CONSTRAINT `fk_transactions_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`)
  ON DELETE RESTRICT ON UPDATE CASCADE;
