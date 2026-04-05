-- PayPool Database Schema V1
-- MySQL 8.0+

-- ----------------------------
-- 1. users - 使用者
-- ----------------------------
CREATE TABLE `users` (
  `user_id`    VARCHAR(20)  NOT NULL,
  `user_name`  VARCHAR(20)  NOT NULL,
  `password`   VARCHAR(256) NOT NULL,
  `role`       VARCHAR(10)  NOT NULL DEFAULT 'employee',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 2. stores - 店家
-- ----------------------------
CREATE TABLE `stores` (
  `store_id`         VARCHAR(20)  NOT NULL,
  `store_name`       VARCHAR(50)  NOT NULL,
  `phone`            VARCHAR(20)  DEFAULT NULL,
  `address`          VARCHAR(100) DEFAULT NULL,
  `min_order_amount` INT          NOT NULL DEFAULT 0 COMMENT '最低訂購金額',
  `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 3. menus - 店家菜單
-- ----------------------------
CREATE TABLE `menus` (
  `menu_id`      INT          NOT NULL AUTO_INCREMENT,
  `store_id`     VARCHAR(20)  NOT NULL,
  `product_name` VARCHAR(50)  NOT NULL,
  `unit_price`   INT          NOT NULL DEFAULT 0,
  `is_available` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否供應中: 1=是, 0=否',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`menu_id`),
  KEY `idx_store_id` (`store_id`),
  CONSTRAINT `fk_menus_store` FOREIGN KEY (`store_id`) REFERENCES `stores` (`store_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 4. orders - 團購訂單
-- ----------------------------
CREATE TABLE `orders` (
  `order_id`   VARCHAR(36)  NOT NULL COMMENT 'UUID',
  `store_id`   VARCHAR(20)  NOT NULL,
  `created_by` VARCHAR(20)  NOT NULL COMMENT '開團者',
  `order_name` VARCHAR(45)  NOT NULL,
  `status`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0=OPEN, 1=CLOSED, 2=SETTLED, -1=CANCELLED, -2=FAILED, -3=DELETED',
  `deadline`   DATETIME     NOT NULL COMMENT '訂單截止時間',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`),
  KEY `idx_status` (`status`),
  KEY `idx_store_id` (`store_id`),
  KEY `idx_created_by` (`created_by`),
  CONSTRAINT `fk_orders_store` FOREIGN KEY (`store_id`) REFERENCES `stores` (`store_id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_orders_user` FOREIGN KEY (`created_by`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 5. order_items - 訂單品項
-- ----------------------------
CREATE TABLE `order_items` (
  `item_id`      INT          NOT NULL AUTO_INCREMENT,
  `order_id`     VARCHAR(36)  NOT NULL,
  `user_id`      VARCHAR(20)  NOT NULL COMMENT '點餐者',
  `menu_id`      INT          NOT NULL COMMENT 'FK → menus',
  `product_name` VARCHAR(50)  NOT NULL COMMENT '下單當下的品名快照',
  `unit_price`   INT          NOT NULL COMMENT '下單當下的單價快照',
  `quantity`     INT          NOT NULL DEFAULT 1,
  `subtotal`     INT GENERATED ALWAYS AS (`unit_price` * `quantity`) STORED COMMENT '小計 (自動計算)',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`item_id`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_menu_id` (`menu_id`),
  CONSTRAINT `fk_order_items_order` FOREIGN KEY (`order_id`) REFERENCES `orders` (`order_id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_order_items_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_order_items_menu` FOREIGN KEY (`menu_id`) REFERENCES `menus` (`menu_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 6. transactions - 交易紀錄
-- ----------------------------
CREATE TABLE `transactions` (
  `transaction_id` VARCHAR(36)  NOT NULL COMMENT 'UUID',
  `user_id`        VARCHAR(20)  NOT NULL,
  `order_id`       VARCHAR(36)  DEFAULT NULL COMMENT '儲值時為 NULL',
  `amount`         INT          NOT NULL COMMENT '交易金額 (正數)',
  `closing_balance` INT         NOT NULL COMMENT '交易後餘額',
  `type`           TINYINT      NOT NULL COMMENT '1=DEBIT(扣款), 2=RECHARGE(儲值)',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_by`     VARCHAR(20)  NOT NULL COMMENT '操作者',
  PRIMARY KEY (`transaction_id`),
  KEY `idx_user_created` (`user_id`, `created_at` DESC),
  KEY `idx_order_id` (`order_id`),
  CONSTRAINT `fk_transactions_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 7. token_blacklist - Token 黑名單
-- ----------------------------
CREATE TABLE `token_blacklist` (
  `token`       VARCHAR(768) NOT NULL,
  `user_id`     VARCHAR(20)  NOT NULL,
  `reason`      VARCHAR(255) DEFAULT NULL,
  `expire_time` DATETIME     NOT NULL COMMENT 'Token 原始過期時間',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`token`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- Seed Data
-- ============================================================

-- 使用者 (密碼皆為 BCrypt 雜湊，明文: test1234)
INSERT INTO `users` (`user_id`, `user_name`, `password`, `role`) VALUES
  ('admin',    '管理員',     '$2a$10$P1cMA0JKgiFJzevbODd/a.xb5rcteKZbUaSmUiv9Dx7p7MOoG8tYa', 'admin'),
  ('alice',    'Alice Chen', '$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW', 'employee'),
  ('bob',      'Bob Wang',   '$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW', 'employee'),
  ('charlie',  'Charlie Li', '$2a$10$XPMeuJdtYd.vXoarK3BdxOpBip8zRR5Ql3/cORtUn/N9G1pfnIAQW', 'employee');

-- 店家
INSERT INTO `stores` (`store_id`, `store_name`, `phone`, `address`, `min_order_amount`) VALUES
  ('store001', '八方雲集(烏日店)',     '04-2336-1168', '台中市烏日區中山路二段193號', 350),
  ('store002', '茶湯會(烏日中山店)',   '04-2338-0201', '台中市烏日區中山路一段436號', 250),
  ('store003', '炒飯超人(公益店)',     '04-2254-9770', '台中市南屯區公益路二段539號', 300);

-- 菜單
INSERT INTO `menus` (`menu_id`, `store_id`, `product_name`, `unit_price`) VALUES
  -- 八方雲集
  (1, 'store001', '招牌鍋貼(10入)',  70),
  (2, 'store001', '韓式辣味鍋貼(10入)', 70),
  (3, 'store001', '酸辣湯',          35),
  (4, 'store001', '玉米濃湯',        35),
  -- 茶湯會
  (5, 'store002', '觀音紅茶(大)',     35),
  (6, 'store002', '翡翠綠茶(大)',     35),
  (7, 'store002', '珍珠奶茶(大)',     55),
  (8, 'store002', '芒果綠茶(大)',     60),
  -- 炒飯超人
  (9,  'store003', '肉絲炒飯',       75),
  (10, 'store003', '蝦仁炒飯',       85),
  (11, 'store003', '牛肉炒飯',       90);

-- 儲值紀錄 (每位員工初始儲值)
INSERT INTO `transactions` (`transaction_id`, `user_id`, `order_id`, `amount`, `closing_balance`, `type`, `created_by`, `created_at`) VALUES
  ('txn-init-alice',   'alice',   NULL, 5000, 5000, 2, 'admin', '2025-04-01 09:00:00'),
  ('txn-init-bob',     'bob',     NULL, 5000, 5000, 2, 'admin', '2025-04-01 09:00:00'),
  ('txn-init-charlie', 'charlie', NULL, 3000, 3000, 2, 'admin', '2025-04-01 09:00:00');

-- 已結算訂單: 午餐鍋貼團
INSERT INTO `orders` (`order_id`, `store_id`, `created_by`, `order_name`, `status`, `deadline`, `created_at`, `updated_at`) VALUES
  ('ord-001', 'store001', 'alice', '午餐鍋貼團', 2, '2025-04-10 12:00:00', '2025-04-10 10:00:00', '2025-04-10 12:30:00');

INSERT INTO `order_items` (`order_id`, `user_id`, `menu_id`, `product_name`, `unit_price`, `quantity`) VALUES
  ('ord-001', 'alice',   1, '招牌鍋貼(10入)',      70, 1),
  ('ord-001', 'alice',   3, '酸辣湯',              35, 1),
  ('ord-001', 'bob',     2, '韓式辣味鍋貼(10入)',   70, 2),
  ('ord-001', 'charlie', 1, '招牌鍋貼(10入)',      70, 1),
  ('ord-001', 'charlie', 4, '玉米濃湯',            35, 1);

-- 午餐鍋貼團扣款紀錄
-- alice:  70+35=105,  餘額 5000-105=4895
-- bob:    70*2=140,   餘額 5000-140=4860
-- charlie:70+35=105,  餘額 3000-105=2895
INSERT INTO `transactions` (`transaction_id`, `user_id`, `order_id`, `amount`, `closing_balance`, `type`, `created_by`, `created_at`) VALUES
  ('txn-ord001-alice',   'alice',   'ord-001', 105, 4895, 1, 'alice', '2025-04-10 12:30:00'),
  ('txn-ord001-bob',     'bob',     'ord-001', 140, 4860, 1, 'alice', '2025-04-10 12:30:00'),
  ('txn-ord001-charlie', 'charlie', 'ord-001', 105, 2895, 1, 'alice', '2025-04-10 12:30:00');

-- 進行中訂單: 下午茶飲料團
INSERT INTO `orders` (`order_id`, `store_id`, `created_by`, `order_name`, `status`, `deadline`, `created_at`) VALUES
  ('ord-002', 'store002', 'bob', '下午茶飲料團', 0, '2025-04-15 15:00:00', '2025-04-15 13:00:00');

INSERT INTO `order_items` (`order_id`, `user_id`, `menu_id`, `product_name`, `unit_price`, `quantity`) VALUES
  ('ord-002', 'alice',   7, '珍珠奶茶(大)', 55, 1),
  ('ord-002', 'bob',     8, '芒果綠茶(大)', 60, 2),
  ('ord-002', 'charlie', 5, '觀音紅茶(大)', 35, 1);
