-- PayPool Schema V6: RBAC 動態權限
-- 四張表:roles / resources / user_roles / role_resources
-- 授權規則存 DB,管理端調整後即時生效(配合 Redis 快取失效)。
-- users.role 舊欄位本版不動(兩段式遷移),資料搬遷至 user_roles。

-- ----------------------------
-- 1. roles - 角色
-- ----------------------------
CREATE TABLE `roles` (
  `role_id`     BIGINT       NOT NULL AUTO_INCREMENT,
  `name`        VARCHAR(30)  NOT NULL COMMENT '角色代碼:SUPER_ADMIN/LEADER/CUSTOMER_SERVICE/MEMBER',
  `description` VARCHAR(100) NULL,
  `status`      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1=啟用, 0=停用',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`role_id`),
  UNIQUE KEY `uk_roles_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 2. resources - 受管制的後端 API 資源
-- ----------------------------
CREATE TABLE `resources` (
  `resource_id` BIGINT       NOT NULL AUTO_INCREMENT,
  `url_pattern` VARCHAR(200) NOT NULL COMMENT 'Spring PathPattern,如 /category/create_category、/campaign/**',
  `http_method` VARCHAR(10)  NOT NULL DEFAULT 'ALL' COMMENT 'GET/POST/PUT/PATCH/DELETE/ALL',
  `name`        VARCHAR(50)  NOT NULL COMMENT '顯示名稱',
  `category`    VARCHAR(30)  NULL COMMENT '分組(管理介面用)',
  `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`resource_id`),
  UNIQUE KEY `uk_resources_pattern_method` (`url_pattern`, `http_method`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 3. user_roles - 使用者↔角色(多對多)
-- ----------------------------
CREATE TABLE `user_roles` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`    VARCHAR(20) NOT NULL,
  `role_id`    BIGINT      NOT NULL,
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_roles` (`user_id`, `role_id`),
  KEY `idx_user_roles_role` (`role_id`),
  CONSTRAINT `fk_user_roles_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_user_roles_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`role_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- 4. role_resources - 角色↔資源(多對多)
-- ----------------------------
CREATE TABLE `role_resources` (
  `id`          BIGINT   NOT NULL AUTO_INCREMENT,
  `role_id`     BIGINT   NOT NULL,
  `resource_id` BIGINT   NOT NULL,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_resources` (`role_id`, `resource_id`),
  KEY `idx_role_resources_resource` (`resource_id`),
  CONSTRAINT `fk_role_resources_role` FOREIGN KEY (`role_id`) REFERENCES `roles` (`role_id`) ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT `fk_role_resources_resource` FOREIGN KEY (`resource_id`) REFERENCES `resources` (`resource_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============================================================
-- Seed Data
-- ============================================================

INSERT INTO `roles` (`name`, `description`) VALUES
  ('SUPER_ADMIN',      '超級管理員(平台方,恆具全部權限——授權層程式碼不變量,不依賴 role_resources)'),
  ('LEADER',           '團長(管理自己發起的團購活動與商品)'),
  ('CUSTOMER_SERVICE', '客服(僅查看訂單)'),
  ('MEMBER',           '一般成員(跟團)');

-- 既有受管制端點登記為資源。
-- 不掛任何 role_resources:登記後預設無角色可用,僅超級管理員(程式碼不變量)可呼叫,
-- 與遷移前 @PreAuthorize("hasRole('ADMIN')") 行為等價。
INSERT INTO `resources` (`url_pattern`, `http_method`, `name`, `category`) VALUES
  ('/category/create_category', 'POST',  '建立分類', 'category'),
  ('/category/update_category', 'PATCH', '更新分類', 'category'),
  ('/category/delete_category', 'POST',  '刪除分類', 'category');

-- users.role 現值搬遷:admin → SUPER_ADMIN,employee → MEMBER
INSERT INTO `user_roles` (`user_id`, `role_id`)
SELECT u.`user_id`, r.`role_id`
FROM `users` u
JOIN `roles` r ON r.`name` = CASE u.`role`
                               WHEN 'admin'    THEN 'SUPER_ADMIN'
                               WHEN 'employee' THEN 'MEMBER'
                             END
WHERE u.`role` IN ('admin', 'employee');
