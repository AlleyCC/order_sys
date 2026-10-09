-- ----------------------------
-- 1. recharge_orders - 儲值單
-- ----------------------------
CREATE TABLE `recharge_orders` (
  `recharge_id` VARCHAR(36) NOT NULL COMMENT 'UUID,同時作為送給付款通道的商戶單號',
  `user_id`     VARCHAR(20) NOT NULL,
  `amount`      INT         NOT NULL COMMENT '儲值金額 (正整數)',
  `status`      VARCHAR(20) NOT NULL COMMENT 'WAITING / SUCCESS / CLOSED',
  `expire_at`   DATETIME    NOT NULL COMMENT '本地過期時間,晚於通道付款期限',
  `paid_at`     DATETIME    DEFAULT NULL COMMENT '入帳時間',
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`recharge_id`),
  KEY `idx_recharge_status_created` (`status`, `created_at`),
  KEY `idx_recharge_status_expire` (`status`, `expire_at`),
  KEY `idx_recharge_user_created` (`user_id`, `created_at`),
  CONSTRAINT `fk_recharge_orders_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 2. transactions.recharge_id - 儲值流水的來源儲值單
-- UNIQUE 是條件式入帳之外的第二道防線:同一筆儲值單寫不出第二筆流水。
-- MySQL 的 UNIQUE 允許多個 NULL,扣款紀錄與既有種子資料不受影響。
-- ----------------------------
ALTER TABLE `transactions`
  ADD COLUMN `recharge_id` VARCHAR(36) DEFAULT NULL COMMENT '儲值時的來源儲值單,扣款為 NULL' AFTER `order_id`,
  ADD UNIQUE KEY `uk_transactions_recharge` (`recharge_id`),
  ADD CONSTRAINT `fk_transactions_recharge` FOREIGN KEY (`recharge_id`) REFERENCES `recharge_orders` (`recharge_id`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- ----------------------------
-- 3. 會計的人工對單(POST)
-- 未登記的端點預設「登入即可」,漏登會讓任何使用者都能觸發對單。
-- ----------------------------
INSERT INTO `resources` (`url_pattern`, `http_method`, `name`, `category`) VALUES
  ('/admin/transactions/**', 'POST', '後台儲值單人工對單', 'admin');

INSERT INTO `role_resources` (`role_id`, `resource_id`)
SELECT r.`role_id`, res.`resource_id`
FROM `roles` r
JOIN `resources` res ON res.`url_pattern` = '/admin/transactions/**' AND res.`http_method` = 'POST'
WHERE r.`name` = 'ACCOUNTANT';

UPDATE `roles`
SET `description` = '會計(對帳:查詢全部交易紀錄與儲值單、對儲值單人工對單)'
WHERE `name` = 'ACCOUNTANT';
