-- PayPool Schema V10: 站內通知
-- 結算結果原本只走 Redis Pub/Sub 推播,收件人離線時通知就消失。
-- 改為和結算的狀態變化寫在同一個交易,推播只負責盡力送達,收件人上線後查未讀補齊。
--
-- dedup_key:只有需要去重的通知才填(自動重試用完 = 'ABANDONED:{orderId}'),
--   其餘為 NULL。UNIQUE 允許多個 NULL,所以「每次結算失敗都是新的一則」不受影響。
-- order_id 不加外鍵:通知是歷史紀錄,不應擋住訂單的任何操作。
-- content 存寫入當下的完整訊息,之後改文案不會改到歷史通知。

CREATE TABLE `notifications` (
  `notification_id` VARCHAR(36)  NOT NULL COMMENT 'UUID',
  `user_id`         VARCHAR(20)  NOT NULL COMMENT '收件人',
  `order_id`        VARCHAR(36)  DEFAULT NULL,
  `type`            VARCHAR(30)  NOT NULL,
  `content`         VARCHAR(255) NOT NULL,
  `dedup_key`       VARCHAR(64)  DEFAULT NULL COMMENT '需要去重的類型才填',
  `read_at`         DATETIME     DEFAULT NULL COMMENT 'NULL = 未讀',
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`notification_id`),
  UNIQUE KEY `uk_user_dedup` (`user_id`, `dedup_key`),
  KEY `idx_user_unread` (`user_id`, `read_at`, `created_at`),
  CONSTRAINT `fk_notifications_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
